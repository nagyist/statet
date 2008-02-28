/*******************************************************************************
 * Copyright (c) 2007-2008 WalWare/StatET-Project (www.walware.de/goto/statet).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Stephan Wahlbrink - initial API and implementation
 *******************************************************************************/

package de.walware.statet.r.ui.editors;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.AbstractDocument;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.BadPartitioningException;
import org.eclipse.jface.text.BadPositionCategoryException;
import org.eclipse.jface.text.DefaultIndentLineAutoEditStrategy;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.DocumentCommand;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension3;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITypedRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.jface.text.link.LinkedModeUI;
import org.eclipse.jface.text.link.LinkedPosition;
import org.eclipse.jface.text.link.LinkedPositionGroup;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.texteditor.ITextEditorExtension3;

import de.walware.eclipsecommons.ltk.AstInfo;
import de.walware.eclipsecommons.ltk.text.ITokenScanner;
import de.walware.eclipsecommons.ltk.text.StringParseInput;
import de.walware.eclipsecommons.ltk.text.TextUtil;
import de.walware.eclipsecommons.ui.util.UIAccess;

import de.walware.statet.base.ui.sourceeditors.IEditorAdapter;
import de.walware.statet.base.ui.sourceeditors.IEditorInstallable;
import de.walware.statet.r.core.IRCoreAccess;
import de.walware.statet.r.core.RCodeStyleSettings;
import de.walware.statet.r.core.RCodeStyleSettings.IndentationType;
import de.walware.statet.r.core.rsource.IRDocumentPartitions;
import de.walware.statet.r.core.rsource.RHeuristicTokenScanner;
import de.walware.statet.r.core.rsource.RIndentUtil;
import de.walware.statet.r.core.rsource.RSourceIndenter;
import de.walware.statet.r.core.rsource.ast.RAst;
import de.walware.statet.r.core.rsource.ast.RAstNode;
import de.walware.statet.r.core.rsource.ast.RScanner;
import de.walware.statet.r.internal.ui.RUIPlugin;


/**
 * Auto edit strategy for R code:
 *  - auto indent on keys
 *  - special indent with tab key
 *  - auto indent on paste
 *  - auto close of pairs
 */
public class RAutoEditStrategy extends DefaultIndentLineAutoEditStrategy
		implements IEditorInstallable {
	
	private static final char[] CURLY_BRACKETS = new char[] { '{', '}' };
	
	
	private class RealTypeListener implements VerifyKeyListener {
		public void verifyKey(final VerifyEvent event) {
			if (!event.doit) {
				return;
			}
			switch (event.character) {
			case '\t':
				if (event.stateMask != 0) {
					return;
				}
			case '{':
			case '}':
			case '(':
			case ')':
			case '[':
			case '%':
			case '\"':
			case '\'':
				event.doit = !customizeKeyPressed(event.character);
				return;
			case 0x0A:
			case 0x0D:
				if (fEditor3 != null) {
					event.doit = !customizeKeyPressed('\n');
					return;
				}
			}
		}
	};
	
	
	private final RealTypeListener fMyListener;
	private IEditorAdapter fEditor;
	private ITextEditorExtension3 fEditor3;
	private SourceViewer fViewer;
	private IRCoreAccess fRCoreAccess;
	
	private IRegion fValidRange;
	private RHeuristicTokenScanner fScanner;
	private RSourceIndenter fIndenter;
	
	private RCodeStyleSettings fRCodeStyle;
	private IDocument fDocument;
	private boolean fIgnoreCommands = false;
	private REditorOptions fOptions;
	
	
	public RAutoEditStrategy(final IRCoreAccess rCoreAccess, final IEditorAdapter adapter, final TextEditor editor) {
		fRCoreAccess = rCoreAccess;
		fOptions = RUIPlugin.getDefault().getREditorSettings(rCoreAccess.getPrefs());
		fEditor = adapter;
		
		assert (fRCoreAccess != null);
		assert (fEditor != null);
		assert (fOptions != null);
		
		fViewer = fEditor.getSourceViewer();
		// note: at moment, (fEditor3 == null) indicates "console mode"
		fEditor3 = editor;
		fMyListener = new RealTypeListener();
	}
	
	public void install(final IEditorAdapter editor) {
		assert (editor.getSourceViewer() == fViewer);
		fViewer.prependVerifyKeyListener(fMyListener);
	}
	
	public void uninstall() {
		fViewer.removeVerifyKeyListener(fMyListener);
	}
	
	
	private final boolean initCustomization(final int offset, final int c) {
		assert(fDocument != null);
		if (fScanner == null) {
			fScanner = createScanner();
			fIndenter = new RSourceIndenter();
		}
		fRCodeStyle = fRCoreAccess.getRCodeStyle();
		fValidRange = getValidRange(offset, c);
		return (fValidRange != null);
	}
	
	protected RHeuristicTokenScanner createScanner() {
		return new RHeuristicTokenScanner();
	}
	
	protected IRegion getValidRange(final int offset, final int c) {
		return new Region(0, fDocument.getLength());
	}
	
	protected final IDocument getDocument() {
		return fDocument;
	}
	
	private final void quitCustomization() {
		fDocument = null;
		fRCodeStyle = null;
	}
	
	
	private final boolean isSmartInsertEnabled() {
		return ((fEditor3 == null && fOptions.isSmartModeByDefaultEnabled())
				|| (fEditor3 != null && fEditor3.getInsertMode() == ITextEditorExtension3.SMART_INSERT)
				);
	}
	
	private final boolean isInDefaultPartition(final int offset) throws BadLocationException {
		final ITypedRegion partition = TextUtilities.getPartition(fDocument, fScanner.getPartitioning(),
				offset, false);
		if (partition != null && offset != partition.getOffset()
				&& (partition.getType().equals(IRDocumentPartitions.R_STRING)
						|| partition.getType().equals(IRDocumentPartitions.R_INFIX_OPERATOR))) {
			return false; // inside strings
		}
		return true;
	}
	
	private final boolean isClosedString(int offset, final int end, final boolean endVirtual, final char sep) {
		fScanner.configure(fDocument, null);
		boolean in = true; // we start always inside after a sep
		final char[] chars = new char[] { sep, '\\' };
		while (offset < end) {
			offset = fScanner.scanForward(offset, end, chars);
			if (offset == RHeuristicTokenScanner.NOT_FOUND) {
				offset = end;
				break;
			}
			offset++;
			if (fScanner.getChar() == '\\') {
				offset++;
			}
			else {
				in = !in;
			}
		}
		return (offset == end) && (!in ^ endVirtual);
	}
	
	private final boolean isClosedBracket(final int backwardOffset, final int forwardOffset, final int searchType) {
		int[] balance = new int[3];
		balance[searchType]++;
		fScanner.configure(fDocument, IRDocumentPartitions.R_DEFAULT);
		balance = fScanner.computeBracketBalance(backwardOffset, forwardOffset, balance, searchType);
		return (balance[searchType] <= 0);
	}
	
	private int countBackward(final char c, int offset) throws BadLocationException {
		int count = 0;
		while (--offset >= 0 && fDocument.getChar(offset) == c) {
			count++;
		}
		return count;
	}
	
	@Override
	public void customizeDocumentCommand(final IDocument d, final DocumentCommand c) {
		if (fIgnoreCommands || c.doit == false || c.text == null) {
			return;
		}
		if (!isSmartInsertEnabled()) {
			super.customizeDocumentCommand(d, c);
			return;
		}
		
		fDocument = d;
		if (!initCustomization(c.offset, -1)) {
			return;
		}
		try {
			if (!isInDefaultPartition(c.offset)) {
				return;
			}
			if (c.length == 0 && TextUtilities.equals(d.getLegalLineDelimiters(), c.text) != -1) {
				smartIndentOnNewLine(c);
			}
			else if (c.text.length() > 1 && fOptions.isSmartPasteEnabled()) {
				smartPaste(c);
			}
		}
		catch (final Exception e) {
			RUIPlugin.logError(RUIPlugin.INTERNAL_ERROR, "An error occurred while customize a command in R auto edit strategy.", e); //$NON-NLS-1$
		}
		finally {
			quitCustomization();
		}
	}
	
	/**
	 * Second main entry method for real single key presses.
	 * 
	 * @return <code>true</code>, if key was processed by method
	 */
	private boolean customizeKeyPressed(final char c) {
		if (!isSmartInsertEnabled() || !UIAccess.isOkToUse(fViewer)) {
			return false;
		}
		fIgnoreCommands = true;
		fDocument = fViewer.getDocument();
		ITextSelection selection = (ITextSelection) fViewer.getSelection();
		if (!initCustomization(selection.getOffset(), c)) {
			return false;
		}
		try {
			final DocumentCommand command = new DocumentCommand() {};
			command.offset = selection.getOffset();
			command.length = selection.getLength();
			command.doit = true;
			command.shiftsCaret = true;
			command.caretOffset = -1;
			int createLinkedMode = -1;
			final int cEnd = command.offset+command.length;
			
			if (isInDefaultPartition(command.offset)) {
				switch (c) {
				case '\t':
					command.text = "\t"; //$NON-NLS-1$
					if (command.length > 0 &&
							fDocument.getLineOfOffset(command.offset) != fDocument.getLineOfOffset(cEnd)) {
						return false;
					}
					smartIndentOnTab(command);
					break;
				case '}':
					command.text = "}"; //$NON-NLS-1$
					smartIndentOnClosingBracket(command);
					break;
				case '{':
					command.text = "{"; //$NON-NLS-1$
					if (fOptions.isSmartCurlyBracketsEnabled()) {
						if (!isClosedBracket(command.offset, cEnd, 0)) {
							command.text = "{}"; //$NON-NLS-1$
							createLinkedMode = 1;
						}
						else if (fDocument.getChar(cEnd) == '}') {
							createLinkedMode = 1;
						}
					}
					smartIndentOnFirstLineCharDefault2(command);
					break;
				case '(':
					command.text = "("; //$NON-NLS-1$
					if (fOptions.isSmartRoundBracketsEnabled()) {
						if (!isClosedBracket(command.offset, cEnd, 1)) {
							command.text = "()"; //$NON-NLS-1$
							createLinkedMode = 1;
						}
						else if (fDocument.getChar(cEnd) == ')') {
							createLinkedMode = 1;
						}
					}
					break;
				case ')':
					command.text = ")"; //$NON-NLS-1$
					smartIndentOnFirstLineCharDefault2(command);
					break;
				case '[':
					command.text = "["; //$NON-NLS-1$
					if (fOptions.isSmartSquareBracketsEnabled()) {
						if (!isClosedBracket(command.offset, cEnd, 2)) {
							command.text = "[]"; //$NON-NLS-1$
							if (countBackward('[', command.offset) % 2 == 1) {
								createLinkedMode = 2;
							}
							else {
								createLinkedMode = 1;
							}
						}
						else if (fDocument.getChar(cEnd) == ']') {
							createLinkedMode = 1;
						}
					}
					break;
				case '%':
					if (fOptions.isSmartSpecialPercentEnabled()) {
						final IRegion line = fDocument.getLineInformationOfOffset(cEnd);
						fScanner.configure(fDocument, IRDocumentPartitions.R_INFIX_OPERATOR);
						if (fScanner.count(cEnd, line.getOffset()+line.getLength(), '%') % 2 == 0) {
							command.text = "%%"; //$NON-NLS-1$
							createLinkedMode = 1;
							break;
						}
					}
					return false;
				case '\"':
				case '\'':
					if (fOptions.isSmartStringsEnabled()) {
						final IRegion line = fDocument.getLineInformationOfOffset(cEnd);
						if (!isClosedString(cEnd, line.getOffset()+line.getLength(), false, c)) {
							command.text = new String(new char[] { c, c });
							createLinkedMode = 1;
							break;
						}
					}
					return false;
				case '\n':
					command.text = TextUtilities.getDefaultLineDelimiter(fDocument);
					smartIndentOnNewLine(command);
					break;
				}
				
				if (command.text.length() > 0 && fEditor.isEditable(true)) {
					fViewer.getTextWidget().setRedraw(false);
					try {
						fDocument.replace(command.offset, command.length, command.text);
						selection = new TextSelection(fDocument, (command.caretOffset >= 0) ?
								command.caretOffset : command.offset+command.text.length(), 0);
						fViewer.setSelection(selection, true);
						
						if (createLinkedMode >= 0) {
							createLinkedMode(command.offset, c, createLinkedMode);
						}
					}
					finally {
						fViewer.getTextWidget().setRedraw(true);
					}
				}
				return true;
			}
		}
		catch (final Exception e) {
			RUIPlugin.logError(RUIPlugin.INTERNAL_ERROR, "An error occurred while customize key pressed in R auto edit strategy.", e); //$NON-NLS-1$
		}
		finally {
			fIgnoreCommands = false;
			quitCustomization();
		}
		return false;
	}
	
	/**
	 * Generic method to indent lines using the RSourceIndenter, called algorithm 2.
	 * @param c handle to read and save the document informations
	 * @param indentCurrentLine
	 * @param setCaret positive values indicates the line to set the caret
	 */
	private void smartIndentLine2(final DocumentCommand c, final boolean indentCurrentLine, final int setCaret) throws BadLocationException, BadPartitioningException, CoreException {
		if (fEditor3 == null) {
			return;
		}
		final IRegion validRegion = fValidRange;
		
		// new algorithm using RSourceIndenter
		String append;
		int cEnd = c.offset+c.length;
		if (cEnd > validRegion.getOffset()+validRegion.getLength()) {
			return;
		}
		final IRegion cEndLine = fDocument.getLineInformationOfOffset(cEnd);
		int tempEnd = cEndLine.getOffset()+cEndLine.getLength();
		if (tempEnd > validRegion.getOffset()+validRegion.getLength()) {
			tempEnd = validRegion.getOffset()+validRegion.getLength();
		}
		fScanner.configure(fDocument, null);
		if (endsWithNewLine(c.text)) {
			final int nextCharOffset = fScanner.findNonBlankForward(cEnd, tempEnd, false);
			int nextChar;
			if (nextCharOffset >= 0) {
				nextChar = fScanner.getChar();
				cEnd = nextCharOffset;
			}
			else {
				nextChar = -1;
			}
			switch(nextChar) {
			case '}':
				append = ""; //$NON-NLS-1$
				break;
			default:
				append = "DUMMY+"; //$NON-NLS-1$
				break;
			}
		}
		else {
			append = ""; //$NON-NLS-1$
		}
		
		int shift = 0;
		if (c.offset < validRegion.getOffset()
				|| c.offset > validRegion.getOffset()+validRegion.getLength()) {
			return;
		}
		if (c.offset > 2500) {
			final int line = fDocument.getLineOfOffset(c.offset)-30;
			if (line >= 5) {
				shift = fDocument.getLineOffset(line);
				final ITypedRegion partition = ((IDocumentExtension3) fDocument).getPartition(fScanner.getPartitioning(), shift, true);
				if (partition.getType().equals(IRDocumentPartitions.R_STRING)) {
					shift = partition.getOffset();
				}
			}
		}
		if (shift < validRegion.getOffset()) {
			shift = validRegion.getOffset();
		}
		tempEnd = cEnd+1500;
		if (tempEnd > validRegion.getOffset()+validRegion.getLength()) {
			tempEnd = validRegion.getOffset()+validRegion.getLength();
		}
		String text = fDocument.get(shift, c.offset-shift)
				+ c.text + append + fDocument.get(cEnd, tempEnd-cEnd);
		
		// Create temp doc to compute indent
		int dummyCoffset = c.offset-shift;
		int dummyCend = dummyCoffset+c.text.length();
		final AbstractDocument dummyDoc = new Document(text);
		final StringParseInput parseInput = new StringParseInput(text);
		text = null;
		
		// Lines to indent
		int dummyFirstLine = dummyDoc.getLineOfOffset(dummyCoffset);
		final int dummyLastLine = dummyDoc.getLineOfOffset(dummyCend);
		if (!indentCurrentLine) {
			dummyFirstLine++;
		}
		if (dummyFirstLine > dummyLastLine) {
			return;
		}
		
		// Compute indent
		final AstInfo<RAstNode> ast = new AstInfo<RAstNode>(RAst.LEVEL_MINIMAL, 0);
		final RScanner scanner = new RScanner(parseInput, ast);
		ast.root = scanner.scanSourceUnit();
		final TextEdit edit = fIndenter.getIndentEdits(dummyDoc, ast, 0,
				dummyFirstLine, dummyLastLine, fRCoreAccess);
		
		// Apply indent to temp doc
		final Position cPos = new Position(dummyCoffset, c.text.length());
		dummyDoc.addPosition(cPos);
		
		c.length = c.length+edit.getLength()
				// add space between two replacement regions
				// minus overlaps with c.text
				-TextUtil.overlaps(edit.getOffset(), edit.getExclusiveEnd(), dummyCoffset, dummyCend);
		if (edit.getOffset() < dummyCoffset) { // move offset, if edit begins before c
			dummyCoffset = edit.getOffset();
			c.offset = shift+dummyCoffset;
		}
		edit.apply(dummyDoc, TextEdit.NONE);
		
		// Read indent for real doc
		tempEnd = edit.getExclusiveEnd();
		dummyCend = cPos.getOffset()+cPos.getLength();
		if (!cPos.isDeleted && dummyCend > tempEnd) {
			tempEnd = dummyCend;
		}
		c.text = dummyDoc.get(dummyCoffset, tempEnd-dummyCoffset);
		if (setCaret != 0) {
			c.caretOffset = shift+fIndenter.getNewIndentOffset(dummyFirstLine+setCaret-1);
			c.shiftsCaret = false;
		}
		fIndenter.clear();
	}
	
	private final boolean endsWithNewLine(final String text) {
		for (int i = text.length()-1; i >= 0; i--) {
			final char c = text.charAt(i);
			if (c == '\r' || c == '\n') {
				return true;
			}
			if (c != ' ' && c != '\t') {
				return false;
			}
		}
		return false;
	}
	
	
	private void smartIndentOnNewLine(final DocumentCommand c) throws BadLocationException, BadPartitioningException, CoreException {
		final int before = c.offset - 1;
		final int behind = c.offset+c.length;
		if (before >= 0 && behind < fValidRange.getOffset()+fValidRange.getLength()
				&& fDocument.getChar(before) == '{' && fDocument.getChar(behind) == '}') {
			c.text = c.text+c.text;
		}
		try {
			smartIndentLine2(c, false, 1);
		}
		catch (final Exception e) {
			RUIPlugin.logError(RUIPlugin.INTERNAL_ERROR, "An error occurred while customize a command in R auto edit strategy (algorithm 2).", e); //$NON-NLS-1$
			smartIndentAfterNewLine1(c);
		}
	}
	
	private void smartIndentAfterNewLine1(final DocumentCommand c) throws BadLocationException, BadPartitioningException, CoreException {
		final int line = fDocument.getLineOfOffset(c.offset);
		int checkOffset = Math.max(0, c.offset-1);
		final ITypedRegion partition = ((IDocumentExtension3) fDocument).getPartition(fScanner.getPartitioning(), checkOffset, true);
		if (partition.getType().equals(IRDocumentPartitions.R_COMMENT)) {
			checkOffset = partition.getOffset()-1;
		}
		final RIndentUtil util = new RIndentUtil(fDocument, fRCodeStyle);
		int column = util.getLineIndent(line, false)[RIndentUtil.COLUMN_IDX];
		// new block?:
		fScanner.configure(fDocument, null);
		final int match = fScanner.findNonBlankBackward(checkOffset, fDocument.getLineOffset(line)-1, false);
		if (match >= 0 && fDocument.getChar(match) == '{') {
			column = util.getNextLevelColumn(column, 1);
		}
		
		c.text += util.createIndentString(column);
	}
	
	private void smartIndentOnTab(final DocumentCommand c) throws BadLocationException {
		if (fRCodeStyle.getIndentDefaultType() != IndentationType.SPACES) {
			return;
		}
		final int lineOffset = fDocument.getLineOffset(fDocument.getLineOfOffset(c.offset));
		fScanner.configure(fDocument, null);
		if (fScanner.findNonBlankBackward(c.offset-1, lineOffset-1, false) != ITokenScanner.NOT_FOUND) {
			// not first char
			return;
		}
		final RIndentUtil indentation = new RIndentUtil(fDocument, fRCodeStyle);
		final int column = indentation.getColumnAtOffset(c.offset);
		c.text = indentation.createIndentCompletionString(column);
	}
	
	private void smartIndentOnClosingBracket(final DocumentCommand c) throws BadLocationException {
		final int lineOffset = fDocument.getLineOffset(fDocument.getLineOfOffset(c.offset));
		fScanner.configure(fDocument, null);
		if (fScanner.findNonBlankBackward(c.offset-1, lineOffset-1, false) != ITokenScanner.NOT_FOUND) {
			// not first char
			return;
		}
		
		try {
			smartIndentLine2(c, true, 0);
		}
		catch (final Exception e) {
			RUIPlugin.logError(RUIPlugin.INTERNAL_ERROR, "An error occurred while customize a command in R auto edit strategy (algorithm 2).", e); //$NON-NLS-1$
			smartIndentOnClosingBracket1(c);
		}
	}
	
	private void smartIndentOnClosingBracket1(final DocumentCommand c) throws BadLocationException {
		final int lineOffset = fDocument.getLineOffset(fDocument.getLineOfOffset(c.offset));
		final int blockStart = fScanner.findOpeningPeer(lineOffset, CURLY_BRACKETS);
		if (blockStart == ITokenScanner.NOT_FOUND) {
			return;
		}
		final RIndentUtil util = new RIndentUtil(fDocument, fRCodeStyle);
		final int column = util.getLineIndent(fDocument.getLineOfOffset(blockStart), false)[RIndentUtil.COLUMN_IDX];
		c.text = util.createIndentString(column) + c.text;
		c.length += c.offset - lineOffset;
		c.offset = lineOffset;
	}
	
	private void smartIndentOnFirstLineCharDefault2(final DocumentCommand c) throws BadLocationException {
		final int lineOffset = fDocument.getLineOffset(fDocument.getLineOfOffset(c.offset));
		fScanner.configure(fDocument, null);
		if (fScanner.findNonBlankBackward(c.offset-1, lineOffset-1, false) != ITokenScanner.NOT_FOUND) {
			// not first char
			return;
		}
		
		try {
			smartIndentLine2(c, true, 0);
		}
		catch (final Exception e) {
			RUIPlugin.logError(RUIPlugin.INTERNAL_ERROR, "An error occurred while customize a command in R auto edit strategy (algorithm 2).", e); //$NON-NLS-1$
		}
	}
	
	private void smartPaste(final DocumentCommand c) throws BadLocationException {
		final int lineOffset = fDocument.getLineOffset(fDocument.getLineOfOffset(c.offset));
		fScanner.configure(fDocument, null);
		final boolean firstLine = (fScanner.findNonBlankBackward(c.offset-1, lineOffset-1, false) == ITokenScanner.NOT_FOUND);
		try {
			smartIndentLine2(c, firstLine, 0);
		}
		catch (final Exception e) {
			RUIPlugin.logError(RUIPlugin.INTERNAL_ERROR, "An error occurred while customize a command in R auto edit strategy (algorithm 2).", e); //$NON-NLS-1$
		}
	}
	
	
	private void createLinkedMode(int offset, final char type, final int stopChars) throws BadLocationException, BadPositionCategoryException {
		int pos = 0;
		offset++;
		final LinkedPositionGroup group1 = new LinkedPositionGroup();
		final LinkedPosition position = new LinkedPosition(fDocument, offset, pos++);
		group1.addPosition(position);
		
		/* set up linked mode */
		final LinkedModeModel model = new LinkedModeModel();
		model.addGroup(group1);
		model.forceInstall();
		
		final BracketLevel level = new BracketLevel(fDocument, fScanner, position,
				BracketLevel.getType(type), fEditor3 == null);
		
		/* create UI */
		final LinkedModeUI ui = new LinkedModeUI(model, fViewer);
		ui.setCyclingMode(LinkedModeUI.CYCLE_NEVER);
		ui.setExitPosition(fViewer, offset+stopChars, 0, pos);
		ui.setSimpleMode(true);
		ui.setExitPolicy(level);
		ui.enter();
	}
	
}
