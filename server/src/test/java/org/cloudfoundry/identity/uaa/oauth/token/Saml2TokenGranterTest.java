/*
 * *****************************************************************************
 *      Cloud Foundry
 *      Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *
 *      This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *      You may not use this product except in compliance with the License.
 *
 *      This product includes a number of subcomponents with
 *      separate copyright notices and license terms. Your use of these
 *      subcomponents is subject to the terms and conditions of the
 *      subcomponent's license, as noted in the LICENSE file.
 * *****************************************************************************
 */
package org.cloudfoundry.identity.uaa.oauth.token;

import java.io.UnsupportedEncodingException;
import java.security.Security;
import java.time.Clock;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.oauth.UaaOauth2Authentication;
import org.cloudfoundry.identity.uaa.oauth.client.ClientConstants;
import org.cloudfoundry.identity.uaa.user.UaaUserDatabase;
import org.cloudfoundry.identity.uaa.zone.ClientServicesExtension;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.common.DefaultOAuth2AccessToken;
import org.springframework.security.oauth2.common.DefaultOAuth2RefreshToken;
import org.springframework.security.oauth2.common.exceptions.InvalidGrantException;
import org.springframework.security.oauth2.provider.OAuth2Request;
import org.springframework.security.oauth2.provider.OAuth2RequestFactory;
import org.springframework.security.oauth2.provider.TokenRequest;
import org.springframework.security.oauth2.provider.client.BaseClientDetails;
import org.springframework.security.oauth2.provider.token.AuthorizationServerTokenServices;
import org.springframework.security.saml.SamlAuthentication;
import org.springframework.security.saml.saml2.Saml2Object;
import org.springframework.security.saml.saml2.authentication.Assertion;
import org.springframework.security.saml.saml2.metadata.EntityDescriptor;
import org.springframework.security.saml.spi.DefaultSamlAuthentication;
import org.springframework.security.saml.spi.opensaml.OpenSamlImplementation;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.cloudfoundry.identity.uaa.oauth.token.ClaimConstants.JTI;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.GRANT_TYPE_SAML2_BEARER;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.USER_TOKEN_REQUESTING_CLIENT_ID;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.oauth2.common.util.OAuth2Utils.CLIENT_ID;
import static org.springframework.security.oauth2.common.util.OAuth2Utils.GRANT_TYPE;

public class Saml2TokenGranterTest {



    private Saml2TokenGranter granter;
    private Saml2TokenGranter mockedgranter;
    private AuthorizationServerTokenServices tokenServices;
    private ClientServicesExtension clientDetailsService;
    private OAuth2RequestFactory requestFactory;
    private UaaOauth2Authentication authentication;
    private TokenRequest tokenRequest;
    private UaaAuthentication userAuthentication;
    private Map<String,String> requestParameters;
    private BaseClientDetails requestingClient;
    private BaseClientDetails receivingClient;
    private BaseClientDetails passwordClient;
    private SamlAuthentication samltoken;
    private UaaUserDatabase uaaUserDatabase = mock(UaaUserDatabase.class);
    private OpenSamlImplementation implementation;

    @Before
    public void setup() {
        try {
            implementation = new OpenSamlImplementation(Clock.systemUTC());
        } catch (Exception e) { }
        tokenServices = mock(AuthorizationServerTokenServices.class);
        clientDetailsService = mock(ClientServicesExtension.class);
        requestFactory = mock(OAuth2RequestFactory.class);
        authentication = mock(UaaOauth2Authentication.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        ServletRequestAttributes attrs = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attrs);
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        userAuthentication = mock(UaaAuthentication.class);
        granter = new Saml2TokenGranter(
            tokenServices,
            clientDetailsService,
            requestFactory);
        Assertion assertion = mock(Assertion.class);
        samltoken = new DefaultSamlAuthentication(true, assertion, "idp-entity-id", "sp-entity-id",null);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        requestingClient = new BaseClientDetails("requestingId",null,"uaa.user",GRANT_TYPE_SAML2_BEARER, null);
        receivingClient =  new BaseClientDetails("receivingId",null,"test.scope",GRANT_TYPE_SAML2_BEARER, null);
        passwordClient =  new BaseClientDetails("pwdId",null,"test.scope","password", null);
        when(clientDetailsService.loadClientByClientId(eq(requestingClient.getClientId()), anyString())).thenReturn(requestingClient);
        when(clientDetailsService.loadClientByClientId(eq(receivingClient.getClientId()), anyString())).thenReturn(receivingClient);
        requestParameters = new HashMap<>();
        requestParameters.put(USER_TOKEN_REQUESTING_CLIENT_ID, requestingClient.getClientId());
        requestParameters.put(GRANT_TYPE, GRANT_TYPE_SAML2_BEARER);
        requestParameters.put(CLIENT_ID, receivingClient.getClientId());
        tokenRequest = new PublicTokenRequest();
        tokenRequest.setRequestParameters(requestParameters);
    }

    @After
    public void teardown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void test_not_authenticated() throws Exception {
        when(authentication.isAuthenticated()).thenReturn(false);
        granter.validateRequest(tokenRequest);
    }

    @Test
    public void test_not_a_user_authentication() throws Exception {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getUserAuthentication()).thenReturn(null);
        granter.validateRequest(tokenRequest);
    }

    @Test(expected = InvalidGrantException.class)
    public void test_no_grant_type() throws Exception {
        missing_parameter(GRANT_TYPE);
    }

    @Test
    public void test_ensure_that_access_token_is_deleted_and_modified() {
        String tokenId = "access_token";
        DefaultOAuth2AccessToken token = new DefaultOAuth2AccessToken(tokenId);
        DefaultOAuth2RefreshToken refreshToken = new DefaultOAuth2RefreshToken("refresh_token");
        Map<String,Object> info = new HashMap(token.getAdditionalInformation());
        info.put(JTI, token.getValue());
        token.setAdditionalInformation(info);
        token.setRefreshToken(refreshToken);
        token.setExpiration(new Date());
    }

    @Test
    public void test_getAccessToken() {
        Collection me = AuthorityUtils.commaSeparatedStringToAuthorityList("openid,foo.bar,uaa.user,one.read");
        OAuth2Request myReq = new OAuth2Request(requestParameters, receivingClient.getClientId(), receivingClient.getAuthorities(), true, receivingClient.getScope(), receivingClient.getResourceIds(), null, null, null);
        requestingClient.setScope(StringUtils.commaDelimitedListToSet("openid,foo.bar"));
        when(userAuthentication.getAuthorities()).thenReturn(me);
        tokenRequest.setClientId(receivingClient.getClientId());
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getUserAuthentication()).thenReturn(null);
        when(authentication.getUserAuthentication()).thenReturn(userAuthentication);
        when(userAuthentication.isAuthenticated()).thenReturn(true);
        when(requestFactory.createOAuth2Request(receivingClient, tokenRequest)).thenReturn(myReq);
        ReflectionTestUtils.setField(granter, "requestFactory", requestFactory);
        granter.getAccessToken(receivingClient, tokenRequest);
    }

    @Test
    public void test_grant() {
        tokenRequest.setGrantType(requestParameters.get(GRANT_TYPE));
        granter.grant(GRANT_TYPE, tokenRequest);
    }

    @Test
    public void test_oauth2_authentication_with_empty_allowed() {
        OAuth2Request myReq = new OAuth2Request(requestParameters, receivingClient.getClientId(), receivingClient.getAuthorities(), true, receivingClient.getScope(), receivingClient.getResourceIds(), null, null, null);
        BaseClientDetails myClient = new BaseClientDetails(requestingClient);
        List<String> allowedProviders = new LinkedList<String>();
        Map<String, Object> additionalInformation = new LinkedHashMap<>();
        Collection me = AuthorityUtils.commaSeparatedStringToAuthorityList("openid,foo.bar,uaa.user,one.read");
        //when(new DefaultSecurityContextAccessor()).thenReturn((DefaultSecurityContextAccessor) securityContextAccessor);
        mockedgranter = mock(Saml2TokenGranter.class);
        when(mockedgranter.getRequestFactory()).thenReturn(requestFactory);
        when(mockedgranter.validateRequest(tokenRequest)).thenReturn(userAuthentication);
        when(mockedgranter.getOAuth2Authentication(myClient, tokenRequest)).thenCallRealMethod();
        myClient.setScope(StringUtils.commaDelimitedListToSet("openid,foo.bar"));
        additionalInformation.put(ClientConstants.ALLOWED_PROVIDERS, allowedProviders);
        myClient.setAdditionalInformation(additionalInformation);
        when(userAuthentication.getAuthorities()).thenReturn(me);
        when(requestFactory.createOAuth2Request(receivingClient, tokenRequest)).thenReturn(myReq);
        mockedgranter.getOAuth2Authentication(myClient, tokenRequest);
    }

    @Test(expected = InvalidGrantException.class)
    public void test_missing_token_Request() {
        granter.validateRequest(null);
    }

    @Test
    public void happy_day() {
        missing_parameter("non existent");
    }


    protected void missing_parameter(String parameter) {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getUserAuthentication()).thenReturn(null);
        when(authentication.getUserAuthentication()).thenReturn(userAuthentication);
        when(userAuthentication.isAuthenticated()).thenReturn(true);
        requestParameters.remove(parameter);
        tokenRequest = new PublicTokenRequest();
        tokenRequest.setClientId(receivingClient.getClientId());
        tokenRequest.setRequestParameters(requestParameters);
        tokenRequest.setGrantType(requestParameters.get(GRANT_TYPE));
        granter.validateRequest(tokenRequest);
    }

    public static class PublicTokenRequest extends TokenRequest {
        public PublicTokenRequest() {
        }
    }

    EntityDescriptor getMetadata(String xml) {
       try {
           return (EntityDescriptor)implementation.resolve(xml, null, null);
       } catch(Exception e) {
       }
       return null;
    }

    Assertion getAssertion(String xml) {
        try {
            return (Assertion)implementation.resolve(xml, null, null);
        } catch(Exception e) {
        }
        return null;
    }

    String getAssertionXml(Assertion assertion) {
        try {
            return implementation.toXml(assertion);
        } catch(Exception e) {
        }
        return null;
    }

	/*
	 * Unmarshall XML string to OpenSAML XMLObject
	 */
	private Saml2Object unmarshallObject(String xmlString) throws UnsupportedEncodingException {
		return implementation.resolve(xmlString, null, null);
	}

}
