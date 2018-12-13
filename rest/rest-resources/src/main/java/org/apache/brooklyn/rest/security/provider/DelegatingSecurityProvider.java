/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.rest.security.provider;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.http.HttpSession;

import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.config.StringConfigMap;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.rest.BrooklynWebConfig;
import org.apache.brooklyn.rest.security.jaas.BrooklynLoginModule;
import org.apache.brooklyn.util.core.ClassLoaderUtils;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DelegatingSecurityProvider implements SecurityProvider {

    private static final Logger log = LoggerFactory.getLogger(DelegatingSecurityProvider.class);
    protected final ManagementContext mgmt;

    public DelegatingSecurityProvider(ManagementContext mgmt) {
        this.mgmt = mgmt;
        mgmt.addPropertiesReloadListener(new PropertiesListener());
    }
    
    private SecurityProvider delegate;
    private final AtomicLong modCount = new AtomicLong();

    private class PropertiesListener implements ManagementContext.PropertiesReloadListener {
        private static final long serialVersionUID = 8148722609022378917L;

        @Override
        public void reloaded() {
            log.debug("{} reloading security provider", DelegatingSecurityProvider.this);
            synchronized (DelegatingSecurityProvider.this) {
                loadDelegate();
                invalidateExistingSessions();
            }
        }
    }

    public synchronized SecurityProvider getDelegate() {
        if (delegate == null) {
            delegate = loadDelegate();
        }
        return delegate;
    }

    @SuppressWarnings("unchecked")
    private synchronized SecurityProvider loadDelegate() {
        StringConfigMap brooklynProperties = mgmt.getConfig();

        SecurityProvider presetDelegate = brooklynProperties.getConfig(BrooklynWebConfig.SECURITY_PROVIDER_INSTANCE);
        if (presetDelegate!=null) {
            log.info("REST using pre-set security provider " + presetDelegate);
            return presetDelegate;
        }
        
        String className = brooklynProperties.getConfig(BrooklynWebConfig.SECURITY_PROVIDER_CLASSNAME);

        if (delegate != null && BrooklynWebConfig.hasNoSecurityOptions(mgmt.getConfig())) {
            log.debug("{} refusing to change from {}: No security provider set in reloaded properties.",
                    this, delegate);
            return delegate;
        }

        try {
            String bundle = brooklynProperties.getConfig(BrooklynWebConfig.SECURITY_PROVIDER_BUNDLE);
            if (bundle!=null) {
                String bundleVersion = brooklynProperties.getConfig(BrooklynWebConfig.SECURITY_PROVIDER_BUNDLE_VERSION);
                log.info("REST using security provider " + className + " from " + bundle+":"+bundleVersion);
                BundleContext bundleContext = ((ManagementContextInternal)mgmt).getOsgiManager().get().getFramework().getBundleContext();
                delegate = BrooklynLoginModule.loadProviderFromBundle(mgmt, bundleContext, bundle, bundleVersion, className);
            } else {
                log.info("REST using security provider " + className);
                ClassLoaderUtils clu = new ClassLoaderUtils(this, mgmt);
                Class<? extends SecurityProvider> clazz = (Class<? extends SecurityProvider>) clu.loadClass(className);
                delegate = createSecurityProviderInstance(mgmt, clazz);
            }
        } catch (Exception e) {
            log.warn("REST unable to instantiate security provider " + className + "; all logins are being disallowed", e);
            delegate = new BlackholeSecurityProvider();
        }

        // Deprecated in 0.11.0. Add to release notes and remove in next release.
        ((BrooklynProperties)mgmt.getConfig()).put(BrooklynWebConfig.SECURITY_PROVIDER_INSTANCE, delegate);
        mgmt.getScratchpad().put(BrooklynWebConfig.SECURITY_PROVIDER_INSTANCE, delegate);

        return delegate;
    }

    public static SecurityProvider createSecurityProviderInstance(ManagementContext mgmt,
            Class<? extends SecurityProvider> clazz) throws NoSuchMethodException, InstantiationException,
                    IllegalAccessException, InvocationTargetException {
        Constructor<? extends SecurityProvider> constructor;
        try {
            constructor = clazz.getConstructor(ManagementContext.class);
            return constructor.newInstance(mgmt);
        } catch (Exception e) {
            constructor = clazz.getConstructor();
            Object delegateO = constructor.newInstance();
            if (!(delegateO instanceof SecurityProvider)) {
                // if classloaders get mangled it will be a different CL's SecurityProvider
                throw new ClassCastException("Delegate is either not a security provider or has an incompatible classloader: "+delegateO);
            }
            return (SecurityProvider) delegateO;
        }
    }

    /**
     * Causes all existing sessions to be invalidated.
     */
    protected void invalidateExistingSessions() {
        modCount.incrementAndGet();
    }

    @Override
    public boolean isAuthenticated(HttpSession session) {
        if (session == null) return false;
        Object modCountWhenFirstAuthenticated = session.getAttribute(getModificationCountKey());
        boolean authenticated = getDelegate().isAuthenticated(session) &&
                Long.valueOf(modCount.get()).equals(modCountWhenFirstAuthenticated);
        return authenticated;
    }

    @Override
    public boolean authenticate(HttpSession session, String user, String password) {
        boolean authenticated = getDelegate().authenticate(session, user, password);
        if (authenticated) {
            session.setAttribute(getModificationCountKey(), modCount.get());
        }
        if (log.isTraceEnabled() && authenticated) {
            log.trace("User {} authenticated with provider {}", user, getDelegate());
        } else if (!authenticated && log.isDebugEnabled()) {
            log.debug("Failed authentication for user {} with provider {}", user, getDelegate());
        }
        return authenticated;
    }

    @Override
    public boolean logout(HttpSession session) { 
        boolean logout = getDelegate().logout(session);
        if (logout) {
            session.removeAttribute(getModificationCountKey());
        }
        return logout;
    }

    private String getModificationCountKey() {
        return getClass().getName() + ".ModCount";
    }
    
    @Override
    public boolean requiresUserPass() {
        return getDelegate().requiresUserPass();
    }

    @Override
    public String toString() {
        return super.toString()+"["+getDelegate()+"]";
    }
    
}
