/*******************************************************************************
 * Copyright (c) 2010 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.xtext.ui.refactoring.impl;

import static com.google.common.collect.Iterables.*;
import static com.google.common.collect.Lists.*;
import static com.google.common.collect.Sets.*;
import static org.eclipse.ltk.core.refactoring.RefactoringStatus.*;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.ui.refactoring.ElementRenameArguments;
import org.eclipse.xtext.ui.refactoring.IRefactoringUpdateAcceptor;
import org.eclipse.xtext.ui.refactoring.IReferenceUpdater;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;

/**
 * Abstract base class to update the references to renamed elements.
 * 
 * Sorts all references by project and uses a separate resource set for each project to assert proper initialization.
 * Updates are performed in clusters of 20 (default) referring resources. 
 * 
 * @author Jan Koehnlein - Initial contribution and API
 * @author Holger Schill
 */
public abstract class AbstractReferenceUpdater implements IReferenceUpdater {

	@Inject
	private ReferenceDescriptionSorter sorter;

	@Inject
	private RefactoringResourceSetProvider resourceSetProvider;

	public void createReferenceUpdates(ElementRenameArguments elementRenameArguments,
			Iterable<IReferenceDescription> referenceDescriptions, IRefactoringUpdateAcceptor updateAcceptor,
			IProgressMonitor monitor) {
		SubMonitor progress = SubMonitor.convert(monitor, 100);
		progress.beginTask("Sort references by project", 1);
		Multimap<IProject, IReferenceDescription> project2references = sorter.sortByProject(referenceDescriptions);
		SubMonitor allProjectsProgress = progress.newChild(98).setWorkRemaining(project2references.keySet().size());
		for (IProject project : project2references.keySet()) {
			if (allProjectsProgress.isCanceled())
				break;
			Multimap<URI, IReferenceDescription> resource2references = sorter.sortByResource(project2references
					.get(project));
			ResourceSet resourceSet = resourceSetProvider.get(project);
			StatusWrapper status = updateAcceptor.getRefactoringStatus();
			createClusteredReferenceUpdates(elementRenameArguments, resource2references, resourceSet, updateAcceptor,
					status, allProjectsProgress.newChild(1));
		}
	}

	protected void createClusteredReferenceUpdates(ElementRenameArguments elementRenameArguments,
			Multimap<URI, IReferenceDescription> resource2references, ResourceSet resourceSet,
			IRefactoringUpdateAcceptor updateAcceptor, StatusWrapper status, IProgressMonitor monitor) {
		SubMonitor progress = SubMonitor.convert(monitor, resource2references.size() + 1);
		if (loadTargetResources(resourceSet, elementRenameArguments, status, progress.newChild(1))) {
			Set<Resource> targetResources = newHashSet(resourceSet.getResources());
			if (getClusterSize() > 0) {
				Multimap<URI, IReferenceDescription> cluster = HashMultimap.create();
				for (URI referringResourceURI : resource2references.keySet()) {
					cluster.putAll(referringResourceURI, resource2references.get(referringResourceURI));
					if (cluster.keySet().size() == getClusterSize()) {
						unloadNonTargetResources(resourceSet, targetResources);
						createReferenceUpdatesForCluster(elementRenameArguments, cluster, resourceSet, updateAcceptor,
								status, progress.newChild(cluster.size()));
						cluster.clear();
					}
				}
				if (!cluster.isEmpty()) {
					unloadNonTargetResources(resourceSet, targetResources);
					createReferenceUpdatesForCluster(elementRenameArguments, cluster, resourceSet, updateAcceptor,
							status, progress.newChild(cluster.size()));
				}
			} else {
				createReferenceUpdatesForCluster(elementRenameArguments, resource2references, resourceSet,
						updateAcceptor, status, progress);
			}
		}
	}

	protected void unloadNonTargetResources(ResourceSet resourceSet, Set<Resource> targetResources) {
		for (Resource resource : newArrayList(resourceSet.getResources())) {
			if (!targetResources.contains(resource)) {
				resource.unload();
				resourceSet.getResources().remove(resource);
			}
		}
	}

	protected int getClusterSize() {
		return 20;
	}

	protected void createReferenceUpdatesForCluster(ElementRenameArguments elementRenameArguments,
			Multimap<URI, IReferenceDescription> resource2references, ResourceSet resourceSet,
			IRefactoringUpdateAcceptor updateAcceptor, StatusWrapper status, IProgressMonitor monitor) {
		SubMonitor progress = SubMonitor.convert(monitor, 100);
		List<URI> unloadableResources = loadReferringResources(resourceSet, resource2references.keySet(), status,
				progress.newChild(10));
		for (URI unloadableResouce : unloadableResources)
			resource2references.removeAll(unloadableResouce);
		List<IReferenceDescription> unresolvableReferences = resolveReferenceProxies(resourceSet,
				resource2references.values(), status, progress.newChild(10));
		for (IReferenceDescription unresolvableReference : unresolvableReferences) {
			URI unresolvableReferringResource = unresolvableReference.getSourceEObjectUri().trimFragment();
			resource2references.remove(unresolvableReferringResource, unresolvableReference);
		}
		elementRenameArguments.getRenameStrategy().applyDeclarationChange(elementRenameArguments.getNewName(),
				resourceSet);
		createReferenceUpdates(elementRenameArguments, resource2references, resourceSet, updateAcceptor,
				progress.newChild(80));
		elementRenameArguments.getRenameStrategy().revertDeclarationChange(resourceSet);
	}

	protected List<IReferenceDescription> resolveReferenceProxies(ResourceSet resourceSet,
			Collection<IReferenceDescription> values, StatusWrapper status, IProgressMonitor monitor) {
		List<IReferenceDescription> unresolvedDescriptions = null;
		for (IReferenceDescription referenceDescription : values) {
			EObject sourceEObject = resourceSet.getEObject(referenceDescription.getSourceEObjectUri(), false);
			if (sourceEObject != null) {
				EObject resolvedReference = resolveReference(sourceEObject, referenceDescription);
				if (!resolvedReference.eIsProxy())
					continue;
				else
					handleCannotResolveExistingReference(sourceEObject, referenceDescription, status);
			} else {
				handleCannotLoadReferringElement(referenceDescription, status);
			}
			if (unresolvedDescriptions == null)
				unresolvedDescriptions = newArrayList();
			unresolvedDescriptions.add(referenceDescription);
		}
		return (unresolvedDescriptions == null) ? Collections.<IReferenceDescription> emptyList()
				: unresolvedDescriptions;
	}

	protected EObject resolveReference(EObject referringElement, IReferenceDescription referenceDescription) {
		Object resolvedValue = referringElement.eGet(referenceDescription.getEReference());
		if (referenceDescription.getEReference().isMany()) {
			List<?> list = (List<?>) resolvedValue;
			resolvedValue = list.get(referenceDescription.getIndexInList());
		}
		return (EObject) resolvedValue;
	}

	protected void handleCannotLoadReferringElement(IReferenceDescription referenceDescription, StatusWrapper status) {
		status.add(ERROR, "Cannot find referring element {0}.\nMaybe the index is be corrupt. Consider a rebuild.",
				referenceDescription.getSourceEObjectUri());
	}

	protected void handleCannotResolveExistingReference(EObject sourceEObject,
			IReferenceDescription referenceDescription, StatusWrapper status) {
		status.add(ERROR, "Cannot resolve existing reference.\nMaybe the index is be corrupt. Consider a rebuild.",
				referenceDescription.getSourceEObjectUri());
	}

	protected abstract void createReferenceUpdates(ElementRenameArguments elementRenameArguments,
			Multimap<URI, IReferenceDescription> resource2references, ResourceSet resourceSet,
			IRefactoringUpdateAcceptor updateAcceptor, IProgressMonitor monitor);

	protected boolean loadTargetResources(ResourceSet resourceSet, ElementRenameArguments elementRenameArguments,
			StatusWrapper status, IProgressMonitor monitor) {
		SubMonitor progress = SubMonitor.convert(monitor, "Loading target resource",
				size(elementRenameArguments.getRenamedElementURIs()));
		boolean isSuccess = true;
		for (URI renamedElementURI : elementRenameArguments.getRenamedElementURIs()) {
			EObject renamedElement = resourceSet.getEObject(renamedElementURI, true);
			if (renamedElement == null || renamedElement.eIsProxy()) {
				status.add(ERROR, "Cannot load target element {0}.", renamedElementURI);
				isSuccess = false;
			}
			progress.worked(1);
		}
		return isSuccess;
	}

	protected List<URI> loadReferringResources(ResourceSet resourceSet, Iterable<URI> referringResourceURIs,
			StatusWrapper status, IProgressMonitor monitor) {
		SubMonitor progress = SubMonitor.convert(monitor, "Loading referencing resources", size(referringResourceURIs));
		List<URI> unloadableResources = null;
		for (URI referringResourceURI : referringResourceURIs) {
			Resource referringResource = resourceSet.getResource(referringResourceURI, true);
			if (referringResource == null) {
				status.add(ERROR, "Could not load referring resource ", referringResourceURI);
				if (unloadableResources == null)
					unloadableResources = newArrayList();
				unloadableResources.add(referringResourceURI);
			}
			progress.worked(1);
		}
		return unloadableResources == null ? Collections.<URI> emptyList() : unloadableResources;
	}

}
