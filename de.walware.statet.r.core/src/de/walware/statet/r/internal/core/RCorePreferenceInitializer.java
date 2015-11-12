/*=============================================================================#
 # Copyright (c) 2007-2015 Stephan Wahlbrink (WalWare.de) and others.
 # All rights reserved. This program and the accompanying materials
 # are made available under the terms of the Eclipse Public License v1.0
 # which accompanies this distribution, and is available at
 # http://www.eclipse.org/legal/epl-v10.html
 # 
 # Contributors:
 #     Stephan Wahlbrink - initial API and implementation
 #=============================================================================*/

package de.walware.statet.r.internal.core;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IScopeContext;

import de.walware.ecommons.preferences.PreferencesUtil;
import de.walware.ecommons.preferences.core.Preference;

import de.walware.statet.r.core.RCodeStyleSettings;


/**
 * Preference Initializer for the preferences of 'StatET R Core' plug-in
 */
public class RCorePreferenceInitializer extends AbstractPreferenceInitializer {
	
	
	@Override
	public void initializeDefaultPreferences() {
		final IScopeContext scope= DefaultScope.INSTANCE;
		final Map<Preference<?>, Object> map= new HashMap<>();
		
		new RCodeStyleSettings(0).deliverToPreferencesMap(map);
		
		PreferencesUtil.setPrefValues(scope, map);
	}
	
}
