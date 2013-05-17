/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat, Inc., and individual contributors
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
package org.jboss.weld.resolution;

import static org.jboss.weld.util.cache.LoadingCacheUtils.getCacheValue;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import org.jboss.weld.manager.BeanManagerImpl;

import java.util.HashSet;
import java.util.Set;

/**
 * Implementation of type safe bean resolution
 *
 * @author Pete Muir
 * @author Marius Bogoevici
 * @author Ales Justin
 */
public abstract class TypeSafeResolver<R extends Resolvable, T> {

    private static class ResolvableToBeanSet<R extends Resolvable, T> extends CacheLoader<R, Set<T>> {

        private final TypeSafeResolver<R, T> resolver;

        private ResolvableToBeanSet(TypeSafeResolver<R, T> resolver) {
            this.resolver = resolver;
        }

        public Set<T> load(R from) {
            return resolver.sortResult(resolver.filterResult(resolver.findMatching(from)));
        }

    }

    /*
     * https://issues.jboss.org/browse/WELD-1323
     */
    private static final long RESOLVED_CACHE_UPPER_BOUND;
    private static final long DEFAULT_RESOLVED_CACHE_UPPER_BOUND = 0x100000L;

    static {
        RESOLVED_CACHE_UPPER_BOUND = Long.getLong("org.jboss.weld.resolution.cacheSize", DEFAULT_RESOLVED_CACHE_UPPER_BOUND);
    }

    // The resolved injection points
    private final LoadingCache<R, Set<T>> resolved;
    // The beans to search
    private final Iterable<? extends T> allBeans;
    private final ResolvableToBeanSet<R, T> resolverFunction;
    private final BeanManagerImpl beanManager;


    /**
     * Constructor
     */
    public TypeSafeResolver(Iterable<? extends T> allBeans, final BeanManagerImpl beanManager) {
        this.beanManager = beanManager;
        this.resolverFunction = new ResolvableToBeanSet<R, T>(this);
        this.resolved = CacheBuilder.newBuilder().maximumSize(RESOLVED_CACHE_UPPER_BOUND).build(resolverFunction);
        this.allBeans = allBeans;
    }

    /**
     * Reset all cached resolutions
     */
    public void clear() {
        this.resolved.invalidateAll();
    }

    /**
     * Get the possible beans for the given element
     *
     * @param resolvable The resolving criteria
     * @return An unmodifiable set of matching beans
     */
    public Set<T> resolve(R resolvable, boolean cache) {
        R wrappedResolvable = wrap(resolvable);
        if (cache) {
            return getCacheValue(resolved, wrappedResolvable);
        } else {
            return resolverFunction.load(wrappedResolvable);
        }
    }

    /**
     * Gets the matching beans for binding criteria from a list of beans
     *
     * @param resolvable the resolvable
     * @return A set of filtered beans
     */
    private Set<T> findMatching(R resolvable) {
        Set<T> result = new HashSet<T>();
        for (T bean : getAllBeans(resolvable)) {
            if (matches(resolvable, bean)) {
                result.add(bean);
            }
        }
        @SuppressWarnings("UnnecessaryLocalVariable")
        Iterable<T> iterable = result; // downcast
        return ImmutableSet.copyOf(iterable);
    }

    protected Iterable<? extends T> getAllBeans(R resolvable) {
        return allBeans;
    }

    protected Iterable<? extends T> getAllBeans() {
        return allBeans;
    }

    protected abstract Set<T> filterResult(Set<T> matched);

    protected abstract Set<T> sortResult(Set<T> matched);

    protected abstract boolean matches(R resolvable, T t);

    /**
     * allows subclasses to wrap a resolvable before it is resolved
     */
    protected R wrap(R resolvable) {
        return resolvable;
    }

    public boolean isCached(R resolvable) {
        return resolved.getIfPresent(wrap(resolvable)) != null;
    }

    protected BeanManagerImpl getBeanManager() {
        return beanManager;
    }

    /**
     * Gets a string representation
     *
     * @return A string representation
     */
    @Override
    public String toString() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Resolver\n");
        buffer.append("Resolved injection points: " + resolved.size() + "\n");
        return buffer.toString();
    }
}
