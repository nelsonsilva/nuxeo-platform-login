/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Nelson Silva <nelson.silva@inevo.pt>
 */
package org.nuxeo.ecm.platform.auth.saml;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.platform.api.login.UserIdentificationInfo;
import org.nuxeo.ecm.platform.auth.saml.binding.HTTPPostBinding;
import org.nuxeo.ecm.platform.auth.saml.binding.HTTPRedirectBinding;
import org.nuxeo.ecm.platform.auth.saml.binding.SAMLBinding;
import org.nuxeo.ecm.platform.auth.saml.key.KeyManager;
import org.nuxeo.ecm.platform.auth.saml.slo.SLOProfile;
import org.nuxeo.ecm.platform.auth.saml.slo.SLOProfileImpl;
import org.nuxeo.ecm.platform.auth.saml.sso.WebSSOProfile;
import org.nuxeo.ecm.platform.auth.saml.sso.WebSSOProfileImpl;
import org.nuxeo.ecm.platform.auth.saml.user.EmailBasedUserResolver;
import org.nuxeo.ecm.platform.auth.saml.user.UserResolver;
import org.nuxeo.ecm.platform.ui.web.auth.LoginScreenHelper;
import org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.NuxeoAuthenticationPlugin;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.NuxeoAuthenticationPluginLogoutExtension;
import org.nuxeo.ecm.platform.ui.web.auth.service.LoginProviderLinkComputer;
import org.nuxeo.runtime.api.Framework;
import org.opensaml.DefaultBootstrap;
import org.opensaml.common.SAMLException;
import org.opensaml.common.binding.BasicSAMLMessageContext;
import org.opensaml.common.binding.SAMLMessageContext;
import org.opensaml.common.xml.SAMLConstants;
import org.opensaml.saml2.core.AuthnRequest;
import org.opensaml.saml2.core.LogoutRequest;
import org.opensaml.saml2.encryption.Decrypter;
import org.opensaml.saml2.encryption.EncryptedElementTypeEncryptedKeyResolver;
import org.opensaml.saml2.metadata.*;
import org.opensaml.saml2.metadata.provider.*;
import org.opensaml.security.MetadataCredentialResolver;
import org.opensaml.ws.transport.InTransport;
import org.opensaml.ws.transport.http.HttpServletRequestAdapter;
import org.opensaml.ws.transport.http.HttpServletResponseAdapter;
import org.opensaml.xml.Configuration;
import org.opensaml.xml.ConfigurationException;
import org.opensaml.xml.encryption.ChainingEncryptedKeyResolver;
import org.opensaml.xml.encryption.InlineEncryptedKeyResolver;
import org.opensaml.xml.encryption.SimpleRetrievalMethodEncryptedKeyResolver;
import org.opensaml.xml.io.Marshaller;
import org.opensaml.xml.io.MarshallingException;
import org.opensaml.xml.parse.BasicParserPool;
import org.opensaml.xml.security.credential.Credential;
import org.opensaml.xml.security.keyinfo.KeyInfoCredentialResolver;
import org.opensaml.xml.security.keyinfo.StaticKeyInfoCredentialResolver;
import org.opensaml.xml.signature.SignatureTrustEngine;
import org.opensaml.xml.signature.impl.ExplicitKeySignatureTrustEngine;
import org.opensaml.xml.util.Pair;
import org.opensaml.xml.util.XMLHelper;
import org.w3c.dom.Element;

import org.opensaml.util.URLBuilder;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.util.*;

import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.*;

public class SAMLAuthenticationProvider implements NuxeoAuthenticationPlugin, LoginProviderLinkComputer, NuxeoAuthenticationPluginLogoutExtension {

    private static final Log log = LogFactory.getLog(SAMLAuthenticationProvider.class);

    // SAML Constants
    protected static final String SAML_REQUEST = "SAMLRequest";
    protected static final String SAML_RESPONSE = "SAMLResponse";
    protected static final String SAML_ATTRIBUTES = "SAML_ATTRIBUTES";

    // Supported SAML Bindings
    // TODO: Allow registering new bindings
    protected static List<SAMLBinding> bindings = new ArrayList<>();
    static {
        bindings.add(new HTTPPostBinding());
        bindings.add(new HTTPRedirectBinding());
    }

    // Decryption key resolver
    private static ChainingEncryptedKeyResolver encryptedKeyResolver = new ChainingEncryptedKeyResolver();
    static {
        encryptedKeyResolver.getResolverChain().add(new InlineEncryptedKeyResolver());
        encryptedKeyResolver.getResolverChain().add(new EncryptedElementTypeEncryptedKeyResolver());
        encryptedKeyResolver.getResolverChain().add(new SimpleRetrievalMethodEncryptedKeyResolver());
    }

    // User Resolver
    public static final Class<? extends UserResolver> DEFAULT_USER_RESOLVER_CLASS = EmailBasedUserResolver.class;

    private UserResolver userResolver;

    // Profiles supported by the IdP
    protected Map<String, SAMLProfile> profiles = new HashMap<>();

    private KeyManager keyManager;
    private SignatureTrustEngine trustEngine;
    private Decrypter decrypter;
    private MetadataProvider metadataProvider;

    @Override
    public void initPlugin(Map<String, String> parameters) {

        // Initialize the User Resolver
        try {
            userResolver = DEFAULT_USER_RESOLVER_CLASS.newInstance();
        } catch (Exception e) {
            log.error("Failed to instantiate UserResolver", e);
        }

        // Initialize the OpenSAML library
        try {
            DefaultBootstrap.bootstrap();
        } catch (ConfigurationException e) {
            log.error("Failed to bootstrap OpenSAML", e);
        }

        // Read the IdP metadata and initialize the supported profiles
        try {
            // Read the IdP metadata
            initializeMetadataProvider(parameters);

            // Setup Signature Trust Engine
            MetadataCredentialResolver metadataCredentialResolver = new MetadataCredentialResolver(metadataProvider);
            trustEngine = new ExplicitKeySignatureTrustEngine(metadataCredentialResolver, org.opensaml.xml.Configuration.getGlobalSecurityConfiguration().getDefaultKeyInfoCredentialResolver());

            // Setup decrypter
            Credential encryptionCredential = getKeyManager().getEncryptionCredential();
            if (encryptionCredential != null) {
                KeyInfoCredentialResolver resolver = new StaticKeyInfoCredentialResolver(encryptionCredential);
                decrypter = new Decrypter(null, resolver, encryptedKeyResolver);
                decrypter.setRootInNewDocument(true);
            }

            // Process IdP roles
            for (RoleDescriptor roleDescriptor : getIdPDescriptor().getRoleDescriptors()) {

                // Web SSO role
                if ( roleDescriptor.getElementQName().equals(IDPSSODescriptor.DEFAULT_ELEMENT_NAME) &&
                     roleDescriptor.isSupportedProtocol(org.opensaml.common.xml.SAMLConstants.SAML20P_NS)) {

                    IDPSSODescriptor idpSSO = (IDPSSODescriptor) roleDescriptor;

                    // SSO
                    for (SingleSignOnService sso : idpSSO.getSingleSignOnServices()) {
                        if (sso.getBinding().equals(SAMLConstants.SAML2_POST_BINDING_URI)) {
                            addProfile(new WebSSOProfileImpl(sso));
                            break;
                        }
                    }

                    // SLO
                    for (SingleLogoutService slo : idpSSO.getSingleLogoutServices()) {
                        if (slo.getBinding().equals(SAMLConstants.SAML2_POST_BINDING_URI)) {
                            addProfile(new SLOProfileImpl(slo));
                            break;
                        }
                    }
                }

                // TODO: Allow registering new profiles

            }

        } catch (MetadataProviderException e) {
            log.error("Failed to register IdP from " + parameters.get("metadata"));
        }

        // contribute icon and link to the Login Screen
        if (parameters.containsKey("name")) {
            LoginScreenHelper.registerLoginProvider(
                    parameters.get("name"),
                    parameters.get("icon"),
                    null,
                    parameters.get("label"),
                    parameters.get("description"),
                    this);
        }
    }

    private void addProfile(SAMLProfile profile) {
        profile.setTrustEngine(trustEngine);
        profile.setDecrypter(decrypter);
        profiles.put(profile.getProfileIdentifier(), profile);
    }

    private void initializeMetadataProvider(Map<String, String> parameters) throws MetadataProviderException {
        AbstractMetadataProvider metadataProvider = null;

        String metadataUrl = parameters.get("metadata");
        int requestTimeout = parameters.containsKey("timeout") ? Integer.parseInt(parameters.get("timeout")) : 5;

        if (metadataUrl.startsWith("http:") || metadataUrl.startsWith("https:")) {
            metadataProvider = new HTTPMetadataProvider(metadataUrl, requestTimeout * 1000);
        } else { // file
            metadataProvider = new FilesystemMetadataProvider(new File(metadataUrl));
        }

        metadataProvider.setParserPool(new BasicParserPool());
        metadataProvider.initialize();

        this.metadataProvider = metadataProvider;
    }

    private EntityDescriptor getIdPDescriptor() throws MetadataProviderException {
        return (EntityDescriptor) metadataProvider.getMetadata();
    }

    /**
     * Returns a Login URL to use with HTTP Redirect
     */
    protected String getSSOUrl(HttpServletRequest request, HttpServletResponse response) {
        WebSSOProfile sso = (WebSSOProfile) profiles.get(WebSSOProfile.PROFILE_URI);
        if (sso == null) {
            return null;
        }

        // Create and populate the context
        SAMLMessageContext context = new BasicSAMLMessageContext();
        populateLocalContext(context);

        // Store the requested URL in the Relay State
        String requestedUrl = getRequestedUrl(request);
        if (requestedUrl != null) {
            context.setRelayState(requestedUrl);
        }

        // Get the encoded SAML request
        String encodedSaml = "";
        try {
            AuthnRequest authnRequest = sso.buildAuthRequest(request);
            // TODO(nfgs) - This should be enough!
            //context.setOutboundSAMLMessage(authnRequest);
            //context.setPeerEntityEndpoint(sso.getEndpoint());
            // TODO(nfgs) : Allow using some other binding
            //new HTTPRedirectDeflateEncoder().encode(context);

            Marshaller marshaller = Configuration.getMarshallerFactory().getMarshaller(authnRequest);
            if (marshaller == null) {
                log.error("Unable to marshall message, no marshaller registered for message object: "
                        + authnRequest.getElementQName());
            }
            Element dom = marshaller.marshall(authnRequest);
            StringWriter buffer = new StringWriter();
            XMLHelper.writeNode(dom, buffer);
            encodedSaml = Base64.encodeBase64String(buffer.toString().getBytes());
        } catch (SAMLException e) {
            log.error("Failed to get SAML Auth request", e);
        } catch (MarshallingException e) {
            log.error("Encountered error marshalling message to its DOM representation", e);
        }

        String loginURL = sso.getEndpoint().getLocation();
        try {
            URLBuilder urlBuilder = new URLBuilder(loginURL);
            urlBuilder.getQueryParams().add(new Pair<>(SAML_REQUEST, encodedSaml));
            loginURL = urlBuilder.buildURL();
        } catch (IllegalArgumentException e) {
            log.error("Error while encoding URL", e);
            return null;
        }
        return loginURL;
    }

    private String getRequestedUrl(HttpServletRequest request) {
        String requestedUrl = (String) request.getAttribute(NXAuthConstants.REQUESTED_URL);
        if (requestedUrl == null) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                requestedUrl = (String) session.getAttribute(NXAuthConstants.START_PAGE_SAVE_KEY);
            }
        }
        return requestedUrl;
    }

    @Override
    public String computeUrl(HttpServletRequest request, String requestedUrl) {
        return getSSOUrl(request, null);
    }

    @Override
    public Boolean handleLoginPrompt(HttpServletRequest request, HttpServletResponse response, String baseURL) {

        String loginURL = getSSOUrl(request, response);

        if (log.isDebugEnabled()) {
            log.debug("Send redirect to " + loginURL);
        }
        try {
            response.sendRedirect(loginURL);
        } catch (IOException e) {
            String errorMessage = String.format(
                    "Unable to send redirect on %s", loginURL);
            log.error(errorMessage, e);
            return false;
        }
        return true;
    }

    // Retrieves user identification information from the request.
    @Override
    public UserIdentificationInfo handleRetrieveIdentity(HttpServletRequest request, HttpServletResponse response) {

        HttpServletRequestAdapter inTransport = new HttpServletRequestAdapter(request);
        SAMLBinding binding = getBinding(inTransport);

        // Check if we support this binding
        if (binding == null) {
            return null;
        }

        HttpServletResponseAdapter outTransport = new HttpServletResponseAdapter(response, request.isSecure());

        // Create and populate the context
        SAMLMessageContext context = new BasicSAMLMessageContext();
        context.setInboundMessageTransport(inTransport);
        context.setOutboundMessageTransport(outTransport);
        populateLocalContext(context);

        // Decode the message
        try {
            context.setCommunicationProfileId(WebSSOProfile.PROFILE_URI);
            binding.decode(context);
        } catch (Exception e) {
            log.error("Error during SAML decoding", e);
            return null;
        }

        // Set Peer context info if needed
        try {
            if (context.getPeerEntityId() == null) {
                context.setPeerEntityId(getIdPDescriptor().getEntityID());
            }
            if (context.getPeerEntityMetadata() == null) {
                context.setPeerEntityMetadata(getIdPDescriptor());
            }
            if (context.getPeerEntityRole() == null) {
                context.setPeerEntityRole(IDPSSODescriptor.DEFAULT_ELEMENT_NAME);
            }
        } catch (MetadataProviderException e) {
            //
        }

        /* Tries to load peer SSL certificate from the inbound message transport using attribute
        X509Certificate[] chain = (X509Certificate[]) context.getInboundMessageTransport().getAttribute(ServletRequestX509CredentialAdapter.X509_CERT_REQUEST_ATTRIBUTE);

        if (chain != null && chain.length > 0) {

            log.debug("Found certificate chain from request {}", chain[0]);
            BasicX509Credential credential = new BasicX509Credential();
            credential.setEntityCertificate(chain[0]);
            credential.setEntityCertificateChain(Arrays.asList(chain));
            context.setPeerSSLCredential(credential);

        }*/

        // Try to authenticate
        SAMLCredential credential = null;

        try {
            WebSSOProfile processor = (WebSSOProfile) profiles.get(context.getCommunicationProfileId());
            if (processor != null) {
                credential = processor.processAuthenticationResponse(context);

                // TODO - Store extra data as a key in the request so apps can use it later in the chain
                Map<String, Object> attributes = new HashMap<>();
                request.setAttribute(SAML_ATTRIBUTES, attributes);

            } else {
                throw new SAMLException("Unsupported profile encountered in the context " + context.getCommunicationProfileId());
            }
        } catch (Exception e) {
            log.debug("Error processing SAML message", e);
            return null;
        }

        String userId = userResolver.findNuxeoUser(credential);

        if (userId == null) {
            sendError(request, "No user found with email: \"" + credential.getNameID().getValue() + "\".");
            return null;
        }

        return  new UserIdentificationInfo(userId, userId);
    }

    protected SAMLBinding getBinding(InTransport transport) {

        for (SAMLBinding binding : bindings) {
            if (binding.supports(transport)) {
                return binding;
            }
        }

        return null;

    }
    private void populateLocalContext(SAMLMessageContext context) {
        // Set local info
        //context.setLocalEntityId(metadataProvider.getHostedSPName());
        context.setLocalEntityRole(SPSSODescriptor.DEFAULT_ELEMENT_NAME);

        // TODO - Generate SPSSO descriptor
        //context.setLocalEntityMetadata(entityDescriptor);
        //context.setLocalEntityRoleMetadata(roleDescriptor);

        context.setMetadataProvider(metadataProvider);
    }

    @Override
    public Boolean needLoginPrompt(HttpServletRequest httpRequest) {
        return true;
    }

    @Override
    public List<String> getUnAuthenticatedURLPrefix() {
        return null;
    }

    /**
     * Returns a Logout URL to use with HTTP Redirect
     */
    protected String getSLOUrl(HttpServletRequest request, HttpServletResponse response) {
        SLOProfile slo = (SLOProfile) profiles.get(SLOProfile.PROFILE_URI);
        if (slo == null) {
            return null;
        }

        String logoutURL = slo.getEndpoint().getLocation();

        // TODO(nfgs) Retrieve the SAMLCredential
        SAMLCredential credential = null;

        // If we have a credential generate a LogoutRequest with the proper session index
        if (credential != null) {

            // Create and populate the context
            SAMLMessageContext context = new BasicSAMLMessageContext();
            populateLocalContext(context);

            try {
                LogoutRequest logoutRequest = slo.buildLogoutRequest(context, credential);
                Marshaller marshaller = Configuration.getMarshallerFactory().getMarshaller(logoutRequest);
                if (marshaller == null) {
                    log.error("Unable to marshall message, no marshaller registered for message object: "
                            + logoutRequest.getElementQName());
                }
                Element dom = marshaller.marshall(logoutRequest);
                StringWriter buffer = new StringWriter();
                XMLHelper.writeNode(dom, buffer);
                String encodedSaml = Base64.encodeBase64String(buffer.toString().getBytes());

                // Add the SAML as parameter
                URLBuilder urlBuilder = new URLBuilder(logoutURL);
                urlBuilder.getQueryParams().add(new Pair<>(SAML_REQUEST, encodedSaml));
                logoutURL = urlBuilder.buildURL();
            } catch (SAMLException e) {
                log.error("Failed to get SAML Logout request", e);
            } catch (MarshallingException e) {
                log.error("Encountered error marshalling message to its DOM representation", e);
            }
        }
        return logoutURL;
    }

    @Override
    public Boolean handleLogout(HttpServletRequest request, HttpServletResponse response) {
        String logoutURL = getSLOUrl(request, response);

        if (log.isDebugEnabled()) {
            log.debug("Send redirect to " + logoutURL);
        }
        try {
            response.sendRedirect(logoutURL);
        } catch (IOException e) {
            String errorMessage = String.format("Unable to send redirect on %s", logoutURL);
            log.error(errorMessage, e);
            return false;
        }
        return true;
    }

    private void sendError(HttpServletRequest req, String msg) {
        req.setAttribute(LOGIN_ERROR, msg);
    }

    private KeyManager getKeyManager() {
        if (keyManager == null) {
            keyManager = Framework.getLocalService(KeyManager.class);
        }
        return keyManager;
    }

}