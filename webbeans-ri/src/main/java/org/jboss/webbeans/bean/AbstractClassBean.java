/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,  
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.webbeans.bean;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;

import javax.webbeans.BindingType;
import javax.webbeans.DefinitionException;
import javax.webbeans.Dependent;
import javax.webbeans.Destructor;
import javax.webbeans.Disposes;
import javax.webbeans.Initializer;
import javax.webbeans.Observes;
import javax.webbeans.Produces;
import javax.webbeans.Production;
import javax.webbeans.UnproxyableDependencyException;
import javax.webbeans.UnserializableDependencyException;
import javax.webbeans.manager.Bean;

import org.jboss.webbeans.CurrentManager;
import org.jboss.webbeans.ManagerImpl;
import org.jboss.webbeans.MetaDataCache;
import org.jboss.webbeans.introspector.AnnotatedClass;
import org.jboss.webbeans.introspector.AnnotatedField;
import org.jboss.webbeans.introspector.AnnotatedMethod;
import org.jboss.webbeans.log.LogProvider;
import org.jboss.webbeans.log.Logging;
import org.jboss.webbeans.util.Reflections;
import org.jboss.webbeans.util.Strings;

/**
 * An abstract bean representation common for class-based beans
 * 
 * @author Pete Muir
 * 
 * @param <T>
 * @param <E>
 */
public abstract class AbstractClassBean<T> extends AbstractBean<T, Class<T>>
{
   // Logger
   private static final LogProvider log = Logging.getLogProvider(AbstractClassBean.class);
   // The item representation
   protected AnnotatedClass<T> annotatedItem;
   // The injectable fields
   private Set<AnnotatedField<Object>> injectableFields;
   // The initializer methods
   private Set<AnnotatedMethod<Object>> initializerMethods;

   /**
    * Constructor
    * 
    * @param type The type
    * @param manager The Web Beans manager
    */
   public AbstractClassBean(AnnotatedClass<T> type, ManagerImpl manager)
   {
      super(manager);
      this.annotatedItem = type;
   }

   /**
    * Initializes the bean and its metadata
    */
   @Override
   protected void init()
   {
      super.init();
      checkRequiredTypesImplemented();
      checkScopeAllowed();
      checkBeanImplementation();
      // TODO Interceptors
      initInitializerMethods();
   }

   protected void checkPassivation()
   {
      for (AnnotatedField<Object> injectableField : injectableFields)
      {
         if (injectableField.isTransient())
         {
            continue;
         }

         Bean<?> bean = CurrentManager.rootManager().resolveByType(injectableField).iterator().next();
         if (Dependent.class.equals(bean.getScopeType()) && !bean.isSerializable())
         {
            throw new UnserializableDependencyException("Dependent Web Beans cannot be injected into non-transient fields of beans declaring a passivating scope");
         }
      }
   }

   /**
    * Initializes the bean type
    */
   protected void initType()
   {
      log.trace("Bean type specified in Java");
      this.type = getAnnotatedItem().getType();
   }

   /**
    * Gets the producer methods
    * 
    * @return A set of producer methods. An empty set is returned if there are
    *         none present
    */
   public Set<AnnotatedMethod<Object>> getProducerMethods()
   {
      return getAnnotatedItem().getAnnotatedMethods(Produces.class);
   }

   /**
    * Gets the producer fields
    * 
    * @return A set of producer fields. An empty set is returned if there are
    *         none present
    */
   @Deprecated
   public Set<AnnotatedField<Object>> getProducerFields()
   {
      return getAnnotatedItem().getAnnotatedFields(Produces.class);
   }

   /**
    * Initializes the injection points
    */
   protected void initInjectionPoints()
   {
      injectableFields = new HashSet<AnnotatedField<Object>>();
      for (AnnotatedField<Object> annotatedField : annotatedItem.getMetaAnnotatedFields(BindingType.class))
      {
         if (!annotatedField.isAnnotationPresent(Produces.class))
         {
            if (annotatedField.isStatic())
            {
               throw new DefinitionException("Don't place binding annotations on static fields " + annotatedField);
            }
            if (annotatedField.isFinal())
            {
               throw new DefinitionException("Don't place binding annotations on final fields " + annotatedField);
            }
            injectableFields.add(annotatedField);
            super.annotatedInjectionPoints.add(annotatedField);
         }
      }
   }

   /**
    * Initializes the initializer methods
    */
   protected void initInitializerMethods()
   {
      initializerMethods = new HashSet<AnnotatedMethod<Object>>();
      for (AnnotatedMethod<Object> annotatedMethod : annotatedItem.getAnnotatedMethods(Initializer.class))
      {
         if (annotatedMethod.isStatic())
         {
            throw new DefinitionException("Initializer method " + annotatedMethod.toString() + " cannot be static");
         }
         else if (annotatedMethod.getAnnotation(Produces.class) != null)
         {
            throw new DefinitionException("Initializer method " + annotatedMethod.toString() + " cannot be annotated @Produces");
         }
         else if (annotatedMethod.getAnnotation(Destructor.class) != null)
         {
            throw new DefinitionException("Initializer method " + annotatedMethod.toString() + " cannot be annotated @Destructor");
         }
         else if (annotatedMethod.getAnnotatedParameters(Disposes.class).size() > 0)
         {
            throw new DefinitionException("Initializer method " + annotatedMethod.toString() + " cannot have parameters annotated @Disposes");
         }
         else if (annotatedMethod.getAnnotatedParameters(Observes.class).size() > 0)
         {
            throw new DefinitionException("Initializer method " + annotatedMethod.toString() + " cannot be annotated @Observes");
         }
         else
         {
            initializerMethods.add(annotatedMethod);
         }
      }
   }

   /**
    * Validates that the required types are implemented
    */
   protected void checkRequiredTypesImplemented()
   {
      for (Class<?> requiredType : getMergedStereotypes().getRequiredTypes())
      {
         log.trace("Checking if required type " + requiredType + " is implemented");
         if (!requiredType.isAssignableFrom(type))
         {
            throw new DefinitionException("Required type " + requiredType + " isn't implemented on " + type);
         }
      }
   }

   /**
    * Validate that the scope type is allowed by the stereotypes on the bean and
    * the bean type
    */
   protected void checkScopeAllowed()
   {
      log.trace("Checking if " + getScopeType() + " is allowed for " + type);
      if (getMergedStereotypes().getSupportedScopes().size() > 0)
      {
         if (!getMergedStereotypes().getSupportedScopes().contains(getScopeType()))
         {
            throw new DefinitionException("Scope " + getScopeType() + " is not an allowed by the stereotype for " + type);
         }
      }
   }

   /**
    * Validates the bean implementation
    */
   protected void checkBeanImplementation()
   {
      if (Reflections.isAbstract(getType()))
      {
         throw new DefinitionException("Web Bean implementation class " + type + " cannot be declared abstract");
      }
      if (MetaDataCache.instance().getScopeModel(getScopeType()).isNormal() && !getAnnotatedItem().isProxyable())
      {
         throw new UnproxyableDependencyException(toString() + " is not proxyable");
      }
   }

   /**
    * Gets the annotated item
    * 
    * @return The annotated item
    */
   @Override
   protected AnnotatedClass<T> getAnnotatedItem()
   {
      return annotatedItem;
   }

   /**
    * Gets the default name
    * 
    * @return The default name
    */
   @Override
   protected String getDefaultName()
   {
      String name = Strings.decapitalize(getType().getSimpleName());
      log.trace("Default name of " + type + " is " + name);
      return name;
   }

   /**
    * Gets the injectable fields
    * 
    * @return The set of injectable fields
    */
   public Set<AnnotatedField<Object>> getInjectableFields()
   {
      return injectableFields;
   }

   /**
    * Gets the annotated methods
    * 
    * @return The set of annotated methods
    */
   public Set<AnnotatedMethod<Object>> getInitializerMethods()
   {
      return initializerMethods;
   }

   /**
    * Gets a string representation
    * 
    * @return The string representation
    */
   @Override
   public String toString()
   {
      return "AbstractClassBean " + getName();
   }

   @Override
   /*
    * Gets the default deployment type
    * 
    * @return The default deployment type
    */
   protected Class<? extends Annotation> getDefaultDeploymentType()
   {
      return Production.class;
   }

}
