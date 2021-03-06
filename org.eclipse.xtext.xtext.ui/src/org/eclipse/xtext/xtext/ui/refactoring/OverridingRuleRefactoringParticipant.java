/*******************************************************************************
 * Copyright (c) 2011 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.xtext.xtext.ui.refactoring;

import static com.google.common.collect.Lists.*;

import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.AbstractRule;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.ui.refactoring.impl.AbstractProcessorBasedRenameParticipant;

import com.google.inject.Inject;

/**
 * @author Jan Koehnlein - Initial contribution and API
 */
@SuppressWarnings("restriction")
public class OverridingRuleRefactoringParticipant extends AbstractProcessorBasedRenameParticipant {

	@Inject
	private RuleOverrideUtil ruleOverrideUtil;
	
	@Override
	protected List<EObject> getRenamedElementsOrProxies(EObject originalTarget) {
		if(originalTarget instanceof AbstractRule) {
			List<IEObjectDescription> overridingRules = ruleOverrideUtil.getOverridingRules((AbstractRule) originalTarget);
			List<EObject> result = newArrayList();
			for(IEObjectDescription overridingRule: overridingRules) {
				result.add(overridingRule.getEObjectOrProxy());
			}
			return result;
		}
		return null;
	}

}
