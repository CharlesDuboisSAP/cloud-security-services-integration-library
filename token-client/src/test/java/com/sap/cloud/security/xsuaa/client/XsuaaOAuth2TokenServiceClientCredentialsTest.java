/**
 * SPDX-FileCopyrightText: 2018-2021 SAP SE or an SAP affiliate company and Cloud Security Client Java contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sap.cloud.security.xsuaa.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sap.cloud.security.config.ClientCredentials;
import com.sap.cloud.security.config.ClientIdentity;
import com.sap.cloud.security.servlet.MDCHelper;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestOperations;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.sap.cloud.security.servlet.MDCHelper.CORRELATION_ID;
import static com.sap.cloud.security.xsuaa.client.OAuth2TokenServiceConstants.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class XsuaaOAuth2TokenServiceClientCredentialsTest {

	OAuth2TokenService cut;
	ClientIdentity clientIdentity;
	URI tokenEndpoint;
	Map<String, String> responseMap;

	@Mock
	RestOperations mockRestOperations;

	@Before
	public void setup() {
		cut = new XsuaaOAuth2TokenService(mockRestOperations);
		clientIdentity = new ClientCredentials("clientid", "mysecretpassword");
		tokenEndpoint = URI.create("https://subdomain.myauth.server.com/oauth/token");

		responseMap = new HashMap<>();
		responseMap.putIfAbsent(ACCESS_TOKEN, "f529.dd6e30.d454677322aaabb0");
		responseMap.putIfAbsent(EXPIRES_IN, "43199");
		responseMap.putIfAbsent(TOKEN_TYPE, "bearer");
	}

	@Test
	public void retrieveToken_throwsOnNullValues() {
		assertThatThrownBy(() -> cut.retrieveAccessTokenViaClientCredentialsGrant(null, clientIdentity, null, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("tokenEndpointUri");

		assertThatThrownBy(() -> cut.retrieveAccessTokenViaClientCredentialsGrant(tokenEndpoint, null, null, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("clientIdentity");
	}

	@Test(expected = OAuth2ServiceException.class)
	public void retrieveToken_throwsIfHttpStatusUnauthorized() throws OAuth2ServiceException {
		Mockito.when(mockRestOperations.postForEntity(any(URI.class), any(HttpEntity.class), eq(Map.class)))
				.thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
		cut.retrieveAccessTokenViaClientCredentialsGrant(tokenEndpoint, clientIdentity,
				null, null);
	}

	@Test(expected = OAuth2ServiceException.class)
	public void retrieveToken_throwsIfHttpStatusNotOk() throws OAuth2ServiceException {
		Mockito.when(mockRestOperations.postForEntity(any(URI.class), any(HttpEntity.class), eq(Map.class)))
				.thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));
		cut.retrieveAccessTokenViaClientCredentialsGrant(tokenEndpoint, clientIdentity,
				null, null);
	}

	@Test
	public void retrieveToken() throws OAuth2ServiceException {
		TokenServiceHttpEntityMatcher tokenHttpEntityMatcher = new TokenServiceHttpEntityMatcher();
		tokenHttpEntityMatcher.setClientCredentials(clientIdentity);
		tokenHttpEntityMatcher.setGrantType(GRANT_TYPE_CLIENT_CREDENTIALS);

		Mockito.when(mockRestOperations
				.postForEntity(
						eq(tokenEndpoint),
						argThat(tokenHttpEntityMatcher),
						eq(Map.class)))
				.thenReturn(new ResponseEntity<>(responseMap, HttpStatus.OK));

		OAuth2TokenResponse accessToken = cut.retrieveAccessTokenViaClientCredentialsGrant(tokenEndpoint,
				clientIdentity,
				null, null);
		assertThat(accessToken.getAccessToken(), is(responseMap.get(ACCESS_TOKEN)));
		assertThat(accessToken.getTokenType(), is(responseMap.get(TOKEN_TYPE)));
		assertNotNull(accessToken.getExpiredAt());
	}

	@Test
	public void retrieveToken_withOptionalParamaters() throws OAuth2ServiceException {
		Map<String, String> additionalParameters = new HashMap<>();
		additionalParameters.put("add-param-1", "value1");
		additionalParameters.put("add-param-2", "value2");

		TokenServiceHttpEntityMatcher tokenHttpEntityMatcher = new TokenServiceHttpEntityMatcher();
		tokenHttpEntityMatcher.setClientCredentials(clientIdentity);
		tokenHttpEntityMatcher.setGrantType(GRANT_TYPE_CLIENT_CREDENTIALS);
		tokenHttpEntityMatcher.addParameters(additionalParameters);

		Mockito.when(mockRestOperations.postForEntity(
				eq(tokenEndpoint),
				argThat(tokenHttpEntityMatcher),
				eq(Map.class)))
				.thenReturn(new ResponseEntity<>(responseMap, HttpStatus.OK));

		OAuth2TokenResponse accessToken = cut.retrieveAccessTokenViaClientCredentialsGrant(tokenEndpoint,
				clientIdentity, null,
				additionalParameters);
		assertThat(accessToken.getAccessToken(), is(responseMap.get(ACCESS_TOKEN)));
	}

	@Test
	public void retrieveToken_requiredParametersCanNotBeOverwritten() throws OAuth2ServiceException {
		TokenServiceHttpEntityMatcher tokenHttpEntityMatcher = new TokenServiceHttpEntityMatcher();
		tokenHttpEntityMatcher.setClientCredentials(clientIdentity);
		tokenHttpEntityMatcher.setGrantType(GRANT_TYPE_CLIENT_CREDENTIALS);

		Mockito.when(mockRestOperations.postForEntity(
				eq(tokenEndpoint),
				argThat(tokenHttpEntityMatcher),
				eq(Map.class)))
				.thenReturn(new ResponseEntity<>(responseMap, HttpStatus.OK));

		Map<String, String> overwrittenGrantType = new HashMap<>();
		overwrittenGrantType.put(GRANT_TYPE, "overwrite-obligatory-param");

		OAuth2TokenResponse accessToken = cut.retrieveAccessTokenViaClientCredentialsGrant(tokenEndpoint,
				clientIdentity, null,
				overwrittenGrantType);
		assertThat(accessToken.getAccessToken(), is(responseMap.get(ACCESS_TOKEN)));
	}

	@Test
	public void correlationIdProvisioning() throws IOException {
		when(mockRestOperations.postForEntity(any(), any(), any())).thenReturn(new ResponseEntity<>(responseMap, HttpStatus.OK));

		ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
		Logger logger = (Logger) LoggerFactory.getLogger(MDCHelper.class);
		listAppender.start();
		logger.addAppender(listAppender);

		cut.retrieveAccessTokenViaClientCredentialsGrant(tokenEndpoint,
				clientIdentity, null, null, Collections.emptyMap(), false);
		Assertions.assertThat(listAppender.list.get(0).getLevel()).isEqualTo(Level.INFO);
		Assertions.assertThat(listAppender.list.get(0).getMessage()).contains("was not found in the MDC");

		MDC.put(CORRELATION_ID, "my-correlation-id");
		cut.retrieveAccessTokenViaClientCredentialsGrant(tokenEndpoint,
				clientIdentity, null, null, Collections.emptyMap(), false);
		Assertions.assertThat(listAppender.list.get(1).getLevel()).isEqualTo(Level.DEBUG);
		Assertions.assertThat(listAppender.list.get(1).getArgumentArray()[1]).isEqualTo(("my-correlation-id"));
		MDC.clear();
	}

}