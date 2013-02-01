/*
 * (C) Copyright 2006-2013 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Nelson Silva <nelson.silva@inevo.pt> - initial API and implementation
 *     Nuxeo
 */

package org.nuxeo.ecm.platform.oauth2.openid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.platform.oauth2.providers.NuxeoOAuth2ServiceProvider;
import org.nuxeo.ecm.platform.oauth2.providers.OAuth2ServiceProviderRegistry;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;

/**
 * @author Nelson Silva <nelson.silva@inevo.pt>
 */
public class OpenIDConnectProviderRegistryImpl extends DefaultComponent
        implements OpenIDConnectProviderRegistry {

    protected static final Log log = LogFactory.getLog(OpenIDConnectProviderRegistryImpl.class);

    public static final String PROVIDER_EP = "providers";

    protected Map<String, OpenIDConnectProvider> providers = new HashMap<String, OpenIDConnectProvider>();

    protected List<OpenIDConnectProviderDescriptor> pendingProviders = new ArrayList<OpenIDConnectProviderDescriptor>();

    protected OAuth2ServiceProviderRegistry getOAuth2ServiceProviderRegistry() {
        return Framework.getLocalService(OAuth2ServiceProviderRegistry.class);
    }

    @Override
    public void registerContribution(Object contribution,
            String extensionPoint, ComponentInstance contributor)
            throws Exception {
        if (PROVIDER_EP.equals(extensionPoint)) {
            OpenIDConnectProviderDescriptor provider = (OpenIDConnectProviderDescriptor) contribution;

            if (provider.getClientId() != null
                    && provider.getClientSecret() != null) {
                System.out.println(provider.getName()
                        + " Registred !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                log.info("OpenId provider for " + provider.getName()
                        + " will be registred at application startup");
                // delay registration because data sources may not be available
                // at this point
                pendingProviders.add(provider);
            } else {
                log.warn("OpenId provider for "
                        + provider.getName()
                        + " is skipped because clientId and/or clientSecret are empty");
            }
        }
    }

    @Override
    public Collection<OpenIDConnectProvider> getProviders() {
        return providers.values();
    }

    @Override
    public Collection<OpenIDConnectProvider> getEnabledProviders() {
        List<OpenIDConnectProvider> result = new ArrayList<OpenIDConnectProvider>();
        for (OpenIDConnectProvider provider : getProviders()) {
            if (provider.isEnabled()) {
                result.add(provider);
            }
        }
        return result;
    }

    @Override
    public OpenIDConnectProvider getProvider(String name) {
        return providers.get(name);
    }

    protected void registerPendingProviders() throws Exception {
        for (OpenIDConnectProviderDescriptor provider : pendingProviders) {
            registerOpenIdProvider(provider);
        }
        pendingProviders.clear();
    }

    protected void registerOpenIdProvider(
            OpenIDConnectProviderDescriptor provider) throws Exception {
        OAuth2ServiceProviderRegistry oauth2ProviderRegistry = getOAuth2ServiceProviderRegistry();

        NuxeoOAuth2ServiceProvider oauth2Provider = oauth2ProviderRegistry.getProvider(provider.getName());

        if (oauth2Provider == null) {
            oauth2Provider = oauth2ProviderRegistry.addProvider(
                    provider.getName(), provider.getTokenServerURL(),
                    provider.getAuthorizationServerURL(),
                    provider.getClientId(), provider.getClientSecret(),
                    Arrays.asList(provider.getScopes()));
        } else {
            log.warn("Provider "
                    + provider.getName()
                    + " is already in the Database, XML contribution  won't overwrite it");
        }
        providers.put(provider.getName(), new OpenIDConnectProvider(
                oauth2Provider, provider.getUserInfoURL(), provider.getIcon(),
                provider.isEnabled()));
    }

    @Override
    public void applicationStarted(ComponentContext context) throws Exception {
        super.applicationStarted(context);
        registerPendingProviders();
    }

}