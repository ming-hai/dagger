/*
 * Copyright (C) 2014 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dagger.internal.codegen;

import com.google.auto.common.AnnotationMirrors;
import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.auto.value.AutoValue;
import com.google.common.base.Equivalence;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimaps;
import com.google.common.util.concurrent.ListenableFuture;
import dagger.Binds;
import dagger.Multibindings;
import dagger.multibindings.Multibinds;
import dagger.producers.Produced;
import dagger.producers.Producer;
import dagger.producers.Production;
import dagger.producers.internal.ProductionImplementation;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleTypeVisitor6;
import javax.lang.model.util.Types;

import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static com.google.auto.common.MoreTypes.asExecutable;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static dagger.internal.codegen.InjectionAnnotations.getQualifier;
import static dagger.internal.codegen.MapKeys.getMapKey;
import static dagger.internal.codegen.MapKeys.getUnwrappedMapKeyType;
import static dagger.internal.codegen.MoreAnnotationMirrors.unwrapOptionalEquivalence;
import static dagger.internal.codegen.MoreAnnotationMirrors.wrapOptionalInEquivalence;
import static javax.lang.model.element.ElementKind.METHOD;

/**
 * Represents a unique combination of {@linkplain TypeMirror type} and
 * {@linkplain Qualifier qualifier} to which binding can occur.
 *
 * @author Gregory Kick
 */
@AutoValue
abstract class Key {

  /** An object that is associated with a {@link Key}. */
  interface HasKey {
    /** The key associated with this object. */
    Key key();
  }

  /**
   * A {@link javax.inject.Qualifier} annotation that provides a unique namespace prefix
   * for the type of this key.
   *
   * Despite documentation in {@link AnnotationMirror}, equals and hashCode aren't implemented
   * to represent logical equality, so {@link AnnotationMirrors#equivalence()}
   * provides this facility.
   */
  abstract Optional<Equivalence.Wrapper<AnnotationMirror>> wrappedQualifier();

  /**
   * The type represented by this key.
   *
   * As documented in {@link TypeMirror}, equals and hashCode aren't implemented to represent
   * logical equality, so {@link MoreTypes#equivalence()} wraps this type.
   */
  abstract Equivalence.Wrapper<TypeMirror> wrappedType();

  /**
   * Absent except for multibinding contributions. Each multibound map and set is represented by a
   * {@linkplain ProvisionBinding.Factory#syntheticMultibinding(DependencyRequest, Iterable)
   * synthetic binding} that depends on the specific contributions to that map or set. Each such
   * contribution binding therefore needs a key that identifies the specific binding, and not only
   * the qualified type that is bound. For those bindings, this is the binding method element.
   */
  abstract Optional<BindingMethodIdentifier> bindingMethodIdentifier();

  /** An object that uniquely identifies a multibinding contribution binding. */
  @AutoValue
  abstract static class BindingMethodIdentifier {
    /**
     * The {@link dagger.Provides} or {@link dagger.producers.Produces} method that contributes to
     * the multibinding.
     */
    abstract ExecutableElement bindingMethod();

    /** The installed module that contains or inherits the {@link #bindingMethod()}. */
    abstract TypeElement contributingModule();

    static BindingMethodIdentifier create(
        ExecutableElement bindingMethod, TypeElement contributingModule) {
      return new AutoValue_Key_BindingMethodIdentifier(bindingMethod, contributingModule);
    }
  }
  
  /**
   * A {@link javax.inject.Qualifier} annotation that provides a unique namespace prefix
   * for the type of this key.
   */
  Optional<AnnotationMirror> qualifier() {
    return unwrapOptionalEquivalence(wrappedQualifier());
  }

  /**
   * The type represented by this key.
   */
  TypeMirror type() {
    return wrappedType().get();
  }

  private static TypeMirror normalize(Types types, TypeMirror type) {
    TypeKind kind = type.getKind();
    return kind.isPrimitive() ? types.boxedClass((PrimitiveType) type).asType() : type;
  }

  /**
   * A key whose {@link #qualifier()} and {@link #bindingMethodIdentifier()} are equivalent to this
   * one's, but with {@code newType} (normalized) as its {@link #type()}.
   */
  private Key withType(Types types, TypeMirror newType) {
    return new AutoValue_Key(
        wrappedQualifier(),
        MoreTypes.equivalence().wrap(normalize(types, newType)),
        bindingMethodIdentifier());
  }

  /**
   * A key whose {@link #qualifier()} and {@link #type()} are equivalent to this one's, but with
   * {@code bindingDeclaration} as its {@link #bindingMethodIdentifier()}.
   */
  private Key withBindingMethodIdentifier(BindingMethodIdentifier bindingMethodIdentifier) {
    return new AutoValue_Key(
        wrappedQualifier(), wrappedType(), Optional.of(bindingMethodIdentifier));
  }

  /**
   * A key whose {@link #qualifier()} and {@link #type()} are equivalent to this one's, but with an
   * absent {@link #bindingMethodIdentifier()}.
   */
  Key withoutBindingMethodIdentifier() {
    return new AutoValue_Key(
        wrappedQualifier(), wrappedType(), Optional.<BindingMethodIdentifier>absent());
  }

  boolean isValidMembersInjectionKey() {
    return !qualifier().isPresent() && !type().getKind().equals(TypeKind.WILDCARD);
  }

  /**
   * Returns true if the key is valid as an implicit key (that is, if it's valid for a just-in-time
   * binding by discovering an {@code @Inject} constructor).
   */
  boolean isValidImplicitProvisionKey(final Types types) {
    // Qualifiers disqualify implicit provisioning.
    if (qualifier().isPresent()) {
      return false;
    }

    return type().accept(new SimpleTypeVisitor6<Boolean, Void>() {
      @Override protected Boolean defaultAction(TypeMirror e, Void p) {
        return false; // Only declared types are allowed.
      }

      @Override public Boolean visitDeclared(DeclaredType type, Void ignored) {
        // Non-classes or abstract classes aren't allowed.
        TypeElement element = MoreElements.asType(type.asElement());
        if (!element.getKind().equals(ElementKind.CLASS)
            || element.getModifiers().contains(Modifier.ABSTRACT)) {
          return false;
        }

        // If the key has type arguments, validate that each type argument is declared.
        // Otherwise the type argument may be a wildcard (or other type), and we can't
        // resolve that to actual types.
        for (TypeMirror arg : type.getTypeArguments()) {
          if (arg.getKind() != TypeKind.DECLARED) {
            return false;
          }
        }

        // Also validate that the key is not the erasure of a generic type.
        // If it is, that means the user referred to Foo<T> as just 'Foo',
        // which we don't allow.  (This is a judgement call -- we *could*
        // allow it and instantiate the type bounds... but we don't.)
        return MoreTypes.asDeclared(element.asType()).getTypeArguments().isEmpty()
            || !types.isSameType(types.erasure(element.asType()), type());
      }
    }, null);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(Key.class)
        .omitNullValues()
        .add("qualifier", qualifier().orNull())
        .add("type", type())
        .add("bindingMethodIdentifier", bindingMethodIdentifier().orNull())
        .toString();
  }

  /**
   * Indexes {@code haveKeys} by {@link HasKey#key()}.
   */
  static <T extends HasKey> ImmutableSetMultimap<Key, T> indexByKey(Iterable<T> haveKeys) {
    return ImmutableSetMultimap.copyOf(
        Multimaps.index(
            haveKeys,
            new Function<HasKey, Key>() {
              @Override
              public Key apply(HasKey hasKey) {
                return hasKey.key();
              }
            }));
  }

  static final class Factory {
    private final Types types;
    private final Elements elements;

    Factory(Types types, Elements elements) {
      this.types = checkNotNull(types);
      this.elements = checkNotNull(elements);
    }

    private TypeElement getSetElement() {
      return elements.getTypeElement(Set.class.getCanonicalName());
    }

    private TypeElement getMapElement() {
      return elements.getTypeElement(Map.class.getCanonicalName());
    }

    private TypeElement getProviderElement() {
      return elements.getTypeElement(Provider.class.getCanonicalName());
    }

    private TypeElement getProducerElement() {
      return elements.getTypeElement(Producer.class.getCanonicalName());
    }

    private TypeElement getClassElement(Class<?> cls) {
      return elements.getTypeElement(cls.getName());
    }

    Key forComponentMethod(ExecutableElement componentMethod) {
      checkNotNull(componentMethod);
      checkArgument(componentMethod.getKind().equals(METHOD));
      TypeMirror returnType = normalize(types, componentMethod.getReturnType());
      return forMethod(componentMethod, returnType);
    }

    Key forProductionComponentMethod(ExecutableElement componentMethod) {
      checkNotNull(componentMethod);
      checkArgument(componentMethod.getKind().equals(METHOD));
      TypeMirror returnType = normalize(types, componentMethod.getReturnType());
      TypeMirror keyType = returnType;
      if (MoreTypes.isTypeOf(ListenableFuture.class, returnType)) {
        keyType = Iterables.getOnlyElement(MoreTypes.asDeclared(returnType).getTypeArguments());
      }
      return forMethod(componentMethod, keyType);
    }

    Key forSubcomponentBuilderMethod(
        ExecutableElement subcomponentBuilderMethod, DeclaredType declaredContainer) {
      checkNotNull(subcomponentBuilderMethod);
      checkArgument(subcomponentBuilderMethod.getKind().equals(METHOD));
      ExecutableType resolvedMethod =
          asExecutable(types.asMemberOf(declaredContainer, subcomponentBuilderMethod));
      TypeMirror returnType = normalize(types, resolvedMethod.getReturnType());
      return forMethod(subcomponentBuilderMethod, returnType);
    }

    Key forProvidesMethod(ExecutableElement method, TypeElement contributingModule) {
      return forProvidesOrProducesMethod(method, contributingModule, getProviderElement());
    }

    Key forProducesMethod(ExecutableElement method, TypeElement contributingModule) {
      return forProvidesOrProducesMethod(method, contributingModule, getProducerElement());
    }

    private Key forProvidesOrProducesMethod(
        ExecutableElement method, TypeElement contributingModule, TypeElement frameworkType) {
      checkArgument(method.getKind().equals(METHOD));
      ExecutableType methodType =
          MoreTypes.asExecutable(
              types.asMemberOf(MoreTypes.asDeclared(contributingModule.asType()), method));
      ContributionType contributionType = ContributionType.fromBindingMethod(method);
      TypeMirror returnType = normalize(types, methodType.getReturnType());
      if (frameworkType.equals(getProducerElement())
          && MoreTypes.isTypeOf(ListenableFuture.class, returnType)) {
        returnType = Iterables.getOnlyElement(MoreTypes.asDeclared(returnType).getTypeArguments());
      }
      TypeMirror keyType =
          bindingMethodKeyType(returnType, method, contributionType, Optional.of(frameworkType));
      Key key = forMethod(method, keyType);
      return contributionType.equals(ContributionType.UNIQUE)
          ? key
          : key.withBindingMethodIdentifier(
              BindingMethodIdentifier.create(method, contributingModule));
    }

    /**
     * Returns the key for a {@link Multibinds @Multibinds} method or a method in a
     * {@link Multibindings @Multibindings} interface.
     *
     * <p>The key's type is either {@code Set<T>} or {@code Map<K, F<V>>}, where {@code F} is either
     * {@link Provider} or {@link Producer}, depending on {@code bindingType}.
     */
    Key forMultibindsMethod(
        BindingType bindingType, ExecutableType executableType, ExecutableElement method) {
      checkArgument(method.getKind().equals(METHOD), "%s must be a method", method);
      TypeElement factoryType =
          elements.getTypeElement(bindingType.frameworkClass().getCanonicalName());
      TypeMirror returnType = normalize(types, executableType.getReturnType());
      TypeMirror keyType =
          MapType.isMap(returnType)
              ? mapOfFrameworkType(
                  MapType.from(returnType).keyType(),
                  factoryType,
                  MapType.from(returnType).valueType())
              : returnType;
      return forMethod(method, keyType);
    }

    /** Returns the key bound by a {@link Binds} method. */
    Key forBindsMethod(ExecutableElement method, ExecutableType methodType) {
      checkArgument(isAnnotationPresent(method, Binds.class));
      ContributionType contributionType = ContributionType.fromBindingMethod(method);
      TypeMirror returnType = normalize(types, methodType.getReturnType());
      TypeMirror keyType =
          bindingMethodKeyType(
              returnType, method, contributionType, Optional.<TypeElement>absent());
      Key key = forMethod(method, keyType);
      return contributionType.equals(ContributionType.UNIQUE)
          ? key
          : key.withBindingMethodIdentifier(
              BindingMethodIdentifier.create(
                  method, MoreElements.asType(method.getEnclosingElement())));
    }

    private TypeMirror bindingMethodKeyType(
        TypeMirror returnType,
        ExecutableElement method,
        ContributionType contributionType,
        Optional<TypeElement> frameworkType) {
      switch (contributionType) {
        case UNIQUE:
          return returnType;
        case SET:
          return types.getDeclaredType(getSetElement(), returnType);
        case MAP:
          if (frameworkType.isPresent()) {
            return mapOfFrameworkType(mapKeyType(method), frameworkType.get(), returnType);
          } else {
            return types.getDeclaredType(getMapElement(), mapKeyType(method), returnType);
          }
        case SET_VALUES:
          // TODO(gak): do we want to allow people to use "covariant return" here?
          checkArgument(SetType.isSet(returnType));
          return returnType;
        default:
          throw new AssertionError();
      }
    }

    /**
     * Returns the key for a binding associated with a {@link DelegateDeclaration}.
     *
     * If {@code delegateDeclaration} is {@code @IntoMap}, transforms the {@code Map<K, V>} key
     * from {@link DelegateDeclaration#key()} to {@code Map<K, FrameworkType<V>>}. If {@code
     * delegateDeclaration} is not a map contribution, its key is returned.
     */
    Key forDelegateBinding(
        DelegateDeclaration delegateDeclaration, Class<?> frameworkType) {
      return delegateDeclaration.contributionType().equals(ContributionType.MAP)
          ? wrapMapValue(delegateDeclaration.key(), frameworkType)
          : delegateDeclaration.key();
    }

    /**
     * Returns {@code Map<KeyType, FrameworkType<ValueType>>}.
     */
    private TypeMirror mapOfFrameworkType(
        TypeMirror keyType, TypeElement frameworkType, TypeMirror valueType) {
      return types.getDeclaredType(
          getMapElement(), keyType, types.getDeclaredType(frameworkType, valueType));
    }

    private TypeMirror mapKeyType(ExecutableElement method) {
      AnnotationMirror mapKeyAnnotation = getMapKey(method).get();
      return MapKeys.unwrapValue(mapKeyAnnotation).isPresent()
          ? getUnwrappedMapKeyType(mapKeyAnnotation.getAnnotationType(), types)
          : mapKeyAnnotation.getAnnotationType();
    }

    private Key forMethod(ExecutableElement method, TypeMirror keyType) {
      return new AutoValue_Key(
          wrapOptionalInEquivalence(getQualifier(method)),
          MoreTypes.equivalence().wrap(keyType),
          Optional.<BindingMethodIdentifier>absent());
    }

    Key forInjectConstructorWithResolvedType(TypeMirror type) {
      return new AutoValue_Key(
          Optional.<Equivalence.Wrapper<AnnotationMirror>>absent(),
          MoreTypes.equivalence().wrap(type),
          Optional.<BindingMethodIdentifier>absent());
    }

    Key forComponent(TypeMirror type) {
      return new AutoValue_Key(
          Optional.<Equivalence.Wrapper<AnnotationMirror>>absent(),
          MoreTypes.equivalence().wrap(normalize(types, type)),
          Optional.<BindingMethodIdentifier>absent());
    }

    Key forMembersInjectedType(TypeMirror type) {
      return new AutoValue_Key(
          Optional.<Equivalence.Wrapper<AnnotationMirror>>absent(),
          MoreTypes.equivalence().wrap(normalize(types, type)),
          Optional.<BindingMethodIdentifier>absent());
    }

    Key forQualifiedType(Optional<AnnotationMirror> qualifier, TypeMirror type) {
      return new AutoValue_Key(
          wrapOptionalInEquivalence(qualifier),
          MoreTypes.equivalence().wrap(normalize(types, type)),
          Optional.<BindingMethodIdentifier>absent());
    }

    Key forProductionExecutor() {
      return forQualifiedType(
          Optional.of(SimpleAnnotationMirror.of(getClassElement(Production.class))),
          getClassElement(Executor.class).asType());
    }

    Key forProductionImplementationExecutor() {
      return forQualifiedType(
          Optional.of(SimpleAnnotationMirror.of(getClassElement(ProductionImplementation.class))),
          getClassElement(Executor.class).asType());
    }

    /**
     * Optionally extract a {@link Key} for the underlying provision binding(s) if such a
     * valid key can be inferred from the given key.  Specifically, if the key represents a
     * {@link Map}{@code <K, V>}, a key of {@code Map<K, Provider<V>>} will be returned.
     */
    Optional<Key> implicitMapProviderKeyFrom(Key possibleMapKey) {
      return maybeWrapMapValue(possibleMapKey, Provider.class);
    }

    /**
     * Optionally extract a {@link Key} for the underlying production binding(s) if such a
     * valid key can be inferred from the given key.  Specifically, if the key represents a
     * {@link Map}{@code <K, V>} or {@code Map<K, Produced<V>>}, a key of
     * {@code Map<K, Producer<V>>} will be returned.
     */
    Optional<Key> implicitMapProducerKeyFrom(Key possibleMapKey) {
      return maybeRewrapMapValue(possibleMapKey, Produced.class, Producer.class)
          .or(maybeWrapMapValue(possibleMapKey, Producer.class));
    }

    /**
     * Keys for map contributions from {@link dagger.Provides} and {@link dagger.producers.Produces}
     * are in the form {@code Map<K, Framework<V>>}, but keys for {@link Binds} methods are just
     * {@code Map<K, V>} since the framework type is not known until graph resolution. This
     * translates from the {@code @Provides}/{@code @Produces} format into the {@code @Binds}
     * format. If {@link Key#type() possibleMapKey.type()} is not a {@code Map<K, Framework<V>>},
     * returns {@code possibleMapKey}.
     */
    Key convertToDelegateKey(Key possibleMapKey) {
      if (!MapType.isMap(possibleMapKey)) {
        return possibleMapKey;
      }
      MapType mapType = MapType.from(possibleMapKey);
      TypeMirror wrappedValueType;
      if (mapType.valuesAreTypeOf(Provider.class)) {
        wrappedValueType = mapType.unwrappedValueType(Provider.class);
      } else if (mapType.valuesAreTypeOf(Producer.class)) {
        wrappedValueType = mapType.unwrappedValueType(Producer.class);
      } else {
        return possibleMapKey;
      }
      return possibleMapKey.withType(
          types, types.getDeclaredType(getMapElement(), mapType.keyType(), wrappedValueType));
    }

    /**
     * Converts a {@link Key} of type {@code Map<K, V>} to {@code Map<K, Provider<V>>}.
     */
    private Key wrapMapValue(Key key, Class<?> newWrappingClass) {
      checkArgument(
          FrameworkTypes.isFrameworkType(
              elements.getTypeElement(newWrappingClass.getName()).asType()));
      return maybeWrapMapValue(key, newWrappingClass).get();
    }

    /**
     * Returns a key of {@link Map}{@code <K, NewWrappingClass<V>>} if the input key represents a
     * {@code Map<K, CurrentWrappingClass<V>>}.
     */
    private Optional<Key> maybeRewrapMapValue(
        Key possibleMapKey, Class<?> currentWrappingClass, Class<?> newWrappingClass) {
      checkArgument(!currentWrappingClass.equals(newWrappingClass));
      if (MapType.isMap(possibleMapKey)) {
        MapType mapType = MapType.from(possibleMapKey);
        if (mapType.valuesAreTypeOf(currentWrappingClass)) {
          TypeElement wrappingElement = getClassElement(newWrappingClass);
          if (wrappingElement == null) {
            // This target might not be compiled with Producers, so wrappingClass might not have an
            // associated element.
            return Optional.absent();
          }
          DeclaredType wrappedValueType =
              types.getDeclaredType(
                  wrappingElement, mapType.unwrappedValueType(currentWrappingClass));
          TypeMirror wrappedMapType =
              types.getDeclaredType(getMapElement(), mapType.keyType(), wrappedValueType);
          return Optional.of(possibleMapKey.withType(types, wrappedMapType));
        }
      }
      return Optional.absent();
    }

    /**
     * Returns a key of {@link Map}{@code <K, WrappingClass<V>>} if the input key represents a
     * {@code Map<K, V>}.
     */
    private Optional<Key> maybeWrapMapValue(Key possibleMapKey, Class<?> wrappingClass) {
      if (MapType.isMap(possibleMapKey)) {
        MapType mapType = MapType.from(possibleMapKey);
        if (!mapType.valuesAreTypeOf(wrappingClass)) {
          TypeElement wrappingElement = getClassElement(wrappingClass);
          if (wrappingElement == null) {
            // This target might not be compiled with Producers, so wrappingClass might not have an
            // associated element.
            return Optional.absent();
          }
          DeclaredType wrappedValueType =
              types.getDeclaredType(wrappingElement, mapType.valueType());
          TypeMirror wrappedMapType =
              types.getDeclaredType(getMapElement(), mapType.keyType(), wrappedValueType);
          return Optional.of(possibleMapKey.withType(types, wrappedMapType));
        }
      }
      return Optional.absent();
    }

    /**
     * Optionally extract a {@link Key} for a {@code Set<T>} if the given key is for
     * {@code Set<Produced<T>>}.
     */
    Optional<Key> implicitSetKeyFromProduced(Key possibleSetOfProducedKey) {
      if (MoreTypes.isType(possibleSetOfProducedKey.type())
          && MoreTypes.isTypeOf(Set.class, possibleSetOfProducedKey.type())) {
        TypeMirror argType =
            MoreTypes.asDeclared(possibleSetOfProducedKey.type()).getTypeArguments().get(0);
        if (MoreTypes.isType(argType) && MoreTypes.isTypeOf(Produced.class, argType)) {
          TypeMirror producedArgType = MoreTypes.asDeclared(argType).getTypeArguments().get(0);
          TypeMirror setType = types.getDeclaredType(getSetElement(), producedArgType);
          return Optional.of(possibleSetOfProducedKey.withType(types, setType));
        }
      }
      return Optional.absent();
    }

    /**
     * Optionally extract a {@link Key} for a {@code Map<K, Provider<V>>} if the given key is for
     * {@code Map<K, Producer<V>>}.
     */
    Optional<Key> implicitProviderMapKeyFromProducer(Key possibleMapOfProducerKey) {
      return maybeRewrapMapValue(possibleMapOfProducerKey, Producer.class, Provider.class);
    }
  }
}
