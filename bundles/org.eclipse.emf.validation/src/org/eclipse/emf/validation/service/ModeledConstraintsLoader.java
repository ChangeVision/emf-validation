/******************************************************************************
 * Copyright (c) 2009, 2023 SAP AG and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 ****************************************************************************/
package org.eclipse.emf.validation.service;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.ResourceLocator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.validation.EMFEventType;
import org.eclipse.emf.validation.internal.EMFModelValidationDebugOptions;
import org.eclipse.emf.validation.internal.EMFModelValidationPlugin;
import org.eclipse.emf.validation.internal.EMFModelValidationStatusCodes;
import org.eclipse.emf.validation.internal.modeled.ClassProvider;
import org.eclipse.emf.validation.internal.modeled.ModeledConstraintDescriptor;
import org.eclipse.emf.validation.internal.modeled.ModeledConstraintProvider;
import org.eclipse.emf.validation.internal.modeled.model.validation.Binding;
import org.eclipse.emf.validation.internal.modeled.model.validation.Category;
import org.eclipse.emf.validation.internal.modeled.model.validation.ClientContext;
import org.eclipse.emf.validation.internal.modeled.model.validation.Constraint;
import org.eclipse.emf.validation.internal.modeled.model.validation.ConstraintBindingsBundle;
import org.eclipse.emf.validation.internal.modeled.model.validation.ConstraintProvider;
import org.eclipse.emf.validation.internal.modeled.model.validation.Constraints;
import org.eclipse.emf.validation.internal.modeled.model.validation.ConstraintsBundle;
import org.eclipse.emf.validation.internal.modeled.model.validation.Event;
import org.eclipse.emf.validation.internal.modeled.model.validation.EventTypesEnum;
import org.eclipse.emf.validation.internal.modeled.model.validation.Parser;
import org.eclipse.emf.validation.internal.modeled.model.validation.Selector;
import org.eclipse.emf.validation.internal.modeled.model.validation.Target;
import org.eclipse.emf.validation.internal.service.ClientContextManager;
import org.eclipse.emf.validation.internal.service.GetBatchConstraintsOperation;
import org.eclipse.emf.validation.internal.service.GetLiveConstraintsOperation;
import org.eclipse.emf.validation.internal.service.IClientContext;
import org.eclipse.emf.validation.internal.service.IProviderDescriptor;
import org.eclipse.emf.validation.internal.service.IProviderOperation;
import org.eclipse.emf.validation.internal.util.Log;
import org.eclipse.emf.validation.internal.util.Trace;
import org.eclipse.emf.validation.internal.util.XmlConstraintFactory;
import org.eclipse.emf.validation.model.CategoryManager;
import org.eclipse.emf.validation.model.IClientSelector;
import org.eclipse.emf.validation.model.IModelConstraint;
import org.osgi.framework.Bundle;

/**
 * <p>
 * Provides services for loading modeled constraints into the validation system.
 * </p>
 *
 * @author Boris Gruschko
 * @since 1.4
 *
 */
public class ModeledConstraintsLoader {

	private Set<URI> loadedConstraintProviders;

	private static final ModeledConstraintsLoader instance = new ModeledConstraintsLoader();

	public static ModeledConstraintsLoader getInstance() {
		return instance;
	}

	private ModeledConstraintsLoader() {
		loadedConstraintProviders = new HashSet<>();
	}

	/**
	 * <p>
	 * Loads constraint bundles into the validation system.
	 * </p>
	 * <p>
	 * This method is provided to support the standalone use case. For loading
	 * bundles in a running platform use either
	 * {@link ModeledConstraintsLoader#loadConstraintBundles(ResourceSet, URI, ModelValidationService, Bundle)}
	 * or use the modeledConstraintProviders extension point.
	 * </p>
	 *
	 * @param rs                optional resource set to be used for resource
	 *                          loading. if this argument is null, a resource set
	 *                          will be created.
	 * @param uri               URI of the resource containing the modeled
	 *                          constraints.
	 * @param validationService {@link ModelValidationService} to be used.
	 * @param resourceLocator   {@link ResourceLocator} for locating message
	 *                          bundles.
	 */
	public void loadConstraintBundles(ResourceSet rs, URI uri, ModelValidationService validationService,
			ResourceLocator resourceLocator) {
		loadConstraintBundles(rs, uri, validationService, new ClassProvider.ClassLoaderProvider(resourceLocator));
	}

	/**
	 * <p>
	 * Loads constraint bundles into the validation system.
	 * </p>
	 *
	 * @param rs                optional resource set to be used for resource
	 *                          loading. if this argument is null, a resource set
	 *                          will be created.
	 * @param uri               URI of the resource containing the modeled
	 *                          constraints.
	 * @param validationService {@link ModelValidationService} to be used.
	 * @param bundle            {@link Bundle} for locating internationalization
	 *                          messages.
	 */
	public void loadConstraintBundles(ResourceSet rs, URI uri, ModelValidationService validationService,
			Bundle bundle) {
		loadConstraintBundles(rs, uri, validationService, new ClassProvider.BundleProvider(bundle));
	}

	/**
	 * Loads constraint categories into the validation system.
	 *
	 * @param rs     rs optional resource set to be used for resource loading.
	 * @param uri    URI of the resource containing the modeled constraints.
	 * @param bundle {@link Bundle} for locating internationalization messages.
	 */
	public void loadCategories(ResourceSet rs, URI uri, Bundle bundle) {
		Resource r = getResource(rs, uri);

		for (EObject o : r.getContents()) {
			if (o instanceof ConstraintsBundle) {
				ConstraintsBundle constraintsBundle = (ConstraintsBundle) o;
				registerCategories(constraintsBundle.getCategories(), new ClassProvider.BundleProvider(bundle));
			}
		}
	}

	private void loadConstraintBundles(ResourceSet rs, URI uri, ModelValidationService validationService,
			ClassProvider classPovider) {
		Resource r = getResource(rs, uri);

		for (EObject o : r.getContents()) {
			if (o instanceof ConstraintsBundle) {
				ConstraintsBundle bundle = (ConstraintsBundle) o;
				registerCategories(bundle.getCategories(), classPovider);

				for (ConstraintProvider provider : bundle.getProviders()) {
					registerConstraintsProvider(provider, validationService, classPovider);
				}

				for (ConstraintBindingsBundle bindings : bundle.getConstraintBindingsBundles()) {
					registerConstraintBindingsBundle(bindings, classPovider);
				}

				for (Parser parser : bundle.getParsers()) {
					registerParser(classPovider, parser);
				}
			}
		}
	}

	private void registerParser(ClassProvider classPovider, Parser parser) {
		try {
			String language = parser.getLanguage();
			String className = parser.getClassName();

			if (language == null) {
				Log.warningMessage(EMFModelValidationStatusCodes.CONSTRAINT_PARSER_TYPE,
						EMFModelValidationStatusCodes.CONSTRAINT_PARSER_TYPE_MSG, new Object[] { className, language });
			} else if (className == null) {
				Log.warningMessage(EMFModelValidationStatusCodes.CONSTRAINT_PARSER_TYPE,
						EMFModelValidationStatusCodes.CONSTRAINT_PARSER_TYPE_MSG, new Object[] { className, language });
			} else {
				((XmlConstraintFactory) ConstraintFactory.getInstance()).registerParser(language,
						(IConstraintParser) classPovider.loadClass(className).getDeclaredConstructor().newInstance());
				Trace.trace(EMFModelValidationDebugOptions.PARSERS,
						"Initialized parser for constraint language: " + language); //$NON-NLS-1$
			}

		} catch (Exception e) {
			Trace.catching(getClass(), "loadConstraintBundles", e); //$NON-NLS-1$
		}
	}

	private void registerConstraintBindingsBundle(ConstraintBindingsBundle bindings, ClassProvider classProvider) {

		for (ClientContext ctx : bindings.getClientContexts()) {
			if (ctx instanceof Selector) {
				assert classProvider != null;

				Selector sel = (Selector) ctx;

				Class<?> selectorClass = null;

				try {
					selectorClass = classProvider.loadClass(sel.getClassName());
				} catch (ClassNotFoundException e) {
					throw new RuntimeException(e);
				}

				IClientSelector instance = null;

				try {
					instance = (IClientSelector) selectorClass.getDeclaredConstructor().newInstance();
				} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
					throw new RuntimeException(e);
				}

				IClientContext oldCtx = ClientContextManager.getInstance().getClientContext(sel.getId());

				if (oldCtx == null) {

					org.eclipse.emf.validation.internal.service.ClientContext newCtx = new org.eclipse.emf.validation.internal.service.ClientContext(
							sel.getId(), EMFModelValidationPlugin.getPluginId());

					newCtx.setSelector(instance);

					ClientContextManager.getInstance().addClientContext(newCtx);
				} else {
					// already present
					throw new RuntimeException("Context already present: " + sel.getId()); //$NON-NLS-1$
				}

			} else {
				throw new UnsupportedOperationException("Enablements not implemented yet."); //$NON-NLS-1$
			}
		}

		for (Binding binding : bindings.getBindings()) {
			for (ClientContext ctx : binding.getClientContexts()) {
				org.eclipse.emf.validation.internal.service.ClientContext context = (org.eclipse.emf.validation.internal.service.ClientContext) ClientContextManager
						.getInstance().getClientContext(ctx.getId());

				if (context == null) {
					throw new IllegalStateException("Context not found"); //$NON-NLS-1$
				}

				for (Constraint constraint : binding.getConstraints()) {
					context.includeConstraint(constraint.getId());
				}

				for (Constraint constraint : binding.getExcludedConstraints()) {
					context.excludeConstraint(constraint.getId());
				}

				for (Category category : binding.getCategories()) {
					context.includeCategory(category.getId());
				}

				for (Category category : binding.getExcludedCategories()) {
					context.excludeCategory(category.getId());
				}
			}
		}

	}

	private void registerCategories(EList<Category> categories, ClassProvider classProvider) {
		for (Category category : categories) {
			org.eclipse.emf.validation.model.Category valCategory = CategoryManager.getInstance()
					.getCategory(category.getPath());

			valCategory.setName(classProvider.bind(category.getName(), null));
			valCategory.setMandatory(category.isMandatory());

			registerCategories(category.getSubCategories(), classProvider);
		}
	}

	private void registerConstraintsProvider(ConstraintProvider provider, ModelValidationService validationService,
			ClassProvider classProvider) {
		URI providerUri = EcoreUtil.getURI(provider);
		if (!loadedConstraintProviders.contains(providerUri)) {
			loadedConstraintProviders.add(providerUri);

			ModelValidationService validationServiceToBeUsed = validationService == null
					? ModelValidationService.getInstance()
					: validationService;

			ModeledConstraintProvider modeledProvider = null;

			if (provider.getClassName() == null) {
				modeledProvider = new ModeledConstraintProvider();

				for (Constraints constraints : provider.getConstraints()) {
					for (Constraint constraint : constraints.getConstraints()) {
						ModeledConstraintDescriptor descriptor = new ModeledConstraintDescriptor(provider.getPluginId(),
								classProvider);
						descriptor.setInitializationData(constraint);

						for (Category category : constraints.getCategories()) {
							CategoryManager.getInstance().getCategory(category.getPath()).addConstraint(descriptor);
							try {
								ConstraintRegistry.getInstance().register(descriptor);
							} catch (ConstraintExistsException e) {
								EMFModelValidationPlugin.getPlugin().getLog().warn(e.getMessage(), e);
								throw new RuntimeException(e);
							}
						}
					}
				}
			} else {
				Class<?> clazz;
				try {
					clazz = classProvider.loadClass(provider.getClassName());
				} catch (ClassNotFoundException e) {
					EMFModelValidationPlugin.getPlugin().getLog().warn(e.getMessage(), e);
					throw new RuntimeException(e);
				}

				if (ModeledConstraintProvider.class.isAssignableFrom(clazz)) {
					try {
						modeledProvider = (ModeledConstraintProvider) clazz.getDeclaredConstructor().newInstance();
					} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
						EMFModelValidationPlugin.getPlugin().getLog().warn(e.getMessage(), e);
						throw new RuntimeException(e);
					}
				} else {
					// TODO: log and throw exception
				}
			}

			modeledProvider.setConstraintProviderModel(provider);
			validationServiceToBeUsed.registerProvider(new _ProviderDescriptor(modeledProvider));

		}
	}

	private Resource getResource(ResourceSet rs, URI uri) {
		if (rs == null) {
			// create a default one if none has been provided
			rs = new ResourceSetImpl();
		}

		Resource r = rs.getResource(uri, true);
		return r;
	}

	private static class _ProviderDescriptor implements IProviderDescriptor {

		private final ModeledConstraintProvider provider;
		private List<String> nsUris = new ArrayList<String>();
	    // map of (String => Boolean) caching whether a namespace is provided
		private final Map<String, Boolean> providedNamespaces = new HashMap<String, Boolean>();

	    public _ProviderDescriptor(ModeledConstraintProvider provider) {
			this.provider = provider;
		}

		@Override
		public IModelConstraintProvider getProvider() {
			return provider;
		}

		@Override
		public boolean isCache() {
			return false;
		}

		@Override
		public boolean isCacheEnabled() {
			return provider.getModel().isCache();
		}

		@Override
		public boolean isXmlProvider() {
			return false;
		}

		@Override
		public boolean provides(IProviderOperation<? extends Collection<? extends IModelConstraint>> operation) {
			if (operation instanceof GetLiveConstraintsOperation) {
				return providesLiveConstraints(operation);
			} else if (operation instanceof GetBatchConstraintsOperation) {
				return providesBatchConstraints(operation);
			}

			return false;

		}

		private boolean providesBatchConstraints(
				IProviderOperation<? extends Collection<? extends IModelConstraint>> operation) {
			if (provider.isLive()) {
				return false;
			}

			GetBatchConstraintsOperation op = (GetBatchConstraintsOperation) operation;

			boolean result = false;

			final EList<Target> targets = provider.getModel().getTarget();
			if (targets.isEmpty()) {
				// as a special case, the absence of any "target" elements
				// indicates that I apply to all elements, features, and events
				// in my namespace
				result = providerHandlesNamespace(op.getEObject());
			} else {
				final EObject eObject = op.getEObject();

				for (Target next : targets) {
					if (providerHandlesEObject(eObject, next)) {
						result = true;
						break;
					}
				}
			}

			return result;
		}

		private boolean providesLiveConstraints(
				IProviderOperation<? extends Collection<? extends IModelConstraint>> operation) {
			if (!provider.isLive()) {
				return false;
			}

			GetLiveConstraintsOperation op = (GetLiveConstraintsOperation) operation;

			boolean result = false;

			final EList<Target> targets = provider.getModel().getTarget();
			if (targets.isEmpty()) {
				// as a special case, the absence of any "target" elements
				// indicates that I apply to all elements, features, and events
				// in my namespace
				result = providerHandlesNamespace(op.getEObject());
			} else {
				final EObject eObject = op.getEObject();

				for (Target next : targets) {
					if (providerHandlesEObject(eObject, next)
							&& providerHandlesEvent(op.getEventType(), next)) {
						result = true;
						break;
					}
				}
			}

			return result;
		}
		
		/**
		 * Helper method to determine whether my provider handles the namespace
		 * in which an EMF object's type is defined.
		 * 
		 * @param eObject an EMF object
		 * @return whether this EMF object's metamodel is recognized by my
		 *     provider
		 */
		private boolean providerHandlesNamespace(EObject eObject) {
			Trace.entering(
					EMFModelValidationDebugOptions.PROVIDERS,
					getClass(),
					"providerHandlesNamespace"); //$NON-NLS-1$

			EPackage epkg = eObject.eClass().getEPackage();
			String targetNsUri = epkg.getNsURI();

			Boolean result = providedNamespaces.get(targetNsUri);
			if (result == null) {
				result = providerHandlesNamespace(targetNsUri, targetNsUri);

				if (result == null) {
					// look for EPackages that this package extends
					Set<EPackage> extended = getExtendedEPackages(epkg);

					for (EPackage next : extended) {
						result = providerHandlesNamespace(targetNsUri, next.getNsURI());
						if (result != null) {
							break;
						}
					}
				}

				if (result == null) {
					// cache a miss on this namespace
					result = false;
				}

				// cache the result for quick lookup next time
				providedNamespaces.put(targetNsUri, result);
			}

			Trace.exiting(getClass(), "providerHandlesNamespace", result); //$NON-NLS-1$

			return result;
		}

	    /**
	     * Queries whether this provider has any constraints for the specified
	     * namespace.
	     * 
	     * @param originalTargetNamespace
	     *            the namespace of the type of the object being validated
	     * @param namespace
	     *            a namespace for which, perhaps, this provider defines
	     *            constraints
	     * @return <code>Boolean.TRUE</code> if this provider targets the
	     *         specified namespace; <code>null</code> otherwise (to trigger a
	     *         continued search)
	     */
	    private Boolean providerHandlesNamespace(String originalTargetNamespace,
	            String namespace) {
			boolean result = false;

			for (String nsUri : nsUris) {
				result = namespace.equalsIgnoreCase(nsUri);

				if (result) {
					if (!namespace.equals(originalTargetNamespace)) {
		                // we found a package that extends the declared target. Cache it
						addTargetNamespaceURI(originalTargetNamespace);
					}
					break;
				}
			}

	        for (EPackage ePackage : provider.getModel().getPackage()) {
	            result = namespace.equalsIgnoreCase(ePackage.getNsURI());

	            if (result) {
	                // we found a pattern match. Cache it
	                addTargetNamespaceURI(originalTargetNamespace);
	                break;
	            }
	        }

			return result ? true : null;
	    }

	    /**
	     * Adds the specified namespace URI to the list of namespaces that I target.
	     * This may be the result of a pattern match or it may be a namespace that
	     * has types that I target by inheritance.
	     * 
	     * @param namespaceURI
	     *            a namespace that I target
	     */
	    private synchronized void addTargetNamespaceURI(String namespaceURI) {
	    	nsUris.add(namespaceURI);
	    }

	    /**
	     * Obtains the set of all packages that the specified <code>epackage</code>
	     * extends, by having classifiers that extend some classifier(s) in those
	     * packages.
	     * 
	     * @param epackage
	     *            a package
	     * @return all of the packages containing classifiers extended by this
	     *         package's classifiers, not including the original package
	     */
		private Set<EPackage> getExtendedEPackages(EPackage epackage) {
			Set<EPackage> result = new HashSet<EPackage>();

			getExtendedEPackages(epackage, result);
			result.remove(epackage);

			return result.isEmpty() ? Collections.<EPackage> emptySet() : result;
		}

	    /**
	     * Recursive helper implementation of
	     * {@link #getExtendedEPackages(EPackage)}.
	     */
		private void getExtendedEPackages(EPackage epackage, Set<EPackage> result) {
			for (Object next : epackage.getEClassifiers()) {
				if (next instanceof EClass) {
					for (EClass zuper : ((EClass) next).getESuperTypes()) {
						EPackage nextPackage = zuper.getEPackage();

						if ((nextPackage != epackage) && !result.contains(nextPackage)) {
							result.add(nextPackage);
							getExtendedEPackages(nextPackage, result);
						}
					}
				}
			}
		}

	    /**
		 * Determines whether my provider can provide any constraints for an
		 * EMF object according to its type.
		 * 
		 * @param eObject an EMF object
		 * @param target the data from a Target model in the provider
		 *    indicates one of the EMF types for which the provider can
		 *    supply constraints
		 * @return whether this EMF object's type may be recognized by the
		 *    provider
		 */
		private boolean providerHandlesEObject(EObject eObject, Target target) {
			if (providerHandlesNamespace(eObject)) {
				EClassifier eClass = target.getEClass();
				if ((eClass != null) && !eClass.isInstance(eObject)) {
					return false;
				}
				return true;
			}
			return false;
		}

		/**
	     * Helper method to determine whether my provider handles the specified
	     * event type.
	     * 
	     * @param eventType
	     *            an EMF event type
	     * @param target
	     *            the data from an Target model in the provider indicates
	     *            one of the EMF events for which the provider can supply
	     *            constraints
	     * @return whether this EMF event type is recognized by my provider
	     */
		private boolean providerHandlesEvent(EMFEventType eventType, Target target) {
			if (target instanceof Event) {
				EventTypesEnum name = ((Event) target).getName();
				if (!eventType.getName().equals(name.name())) {
					return false;
				}
			}
			return true;
		}

	}

}
