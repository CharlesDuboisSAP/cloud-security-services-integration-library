/**
 * SPDX-FileCopyrightText: 2018-2023 SAP SE or an SAP affiliate company and Cloud Security Client Java contributors
 * <p>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sap.cloud.security.token.validation.validators;

import com.sap.cloud.security.config.OAuth2ServiceConfiguration;
import com.sap.cloud.security.config.Service;
import com.sap.cloud.security.token.SapIdToken;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.validation.ValidationResult;
import com.sap.cloud.security.xsuaa.client.OAuth2ServiceEndpointsProvider;
import com.sap.cloud.security.xsuaa.client.OAuth2ServiceException;
import com.sap.cloud.security.xsuaa.client.OAuth2TokenKeyService;
import com.sap.cloud.security.xsuaa.client.OidcConfigurationService;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

public class SapIdJwtSignatureValidatorTest {
	private Token iasToken;
	private Token iasPaasToken;
	private static final URI DUMMY_JKU_URI = URI.create("https://application.myauth.com/jwks_uri");

	private JwtSignatureValidator cut;
	private OAuth2TokenKeyService tokenKeyServiceMock;
	private OAuth2ServiceConfiguration mockConfiguration;
	private OidcConfigurationService oidcConfigServiceMock;
	private OAuth2ServiceEndpointsProvider endpointsProviderMock;

	@Before
	public void setup() throws IOException {
		// no zone-id but iss host == jwks host
		iasPaasToken = new SapIdToken(
				"eyJraWQiOiJkZWZhdWx0LWtpZCIsImFsZyI6IlJTMjU2In0.eyJzdWIiOiJQMTc2OTQ1IiwiYXVkIjoiVDAwMDMxMCIsInVzZXJfdXVpZCI6IjEyMzQ1Njc4OTAiLCJpc3MiOiJodHRwczovL2FwcGxpY2F0aW9uLm15YXV0aC5jb20iLCJleHAiOjY5NzQwMzE2MDAsImdpdmVuX25hbWUiOiJqb2huIiwiZW1haWwiOiJqb2huLmRvZUBlbWFpbC5vcmciLCJjaWQiOiJUMDAwMzEwIn0.Svrb5PriAuHOdhTFXiicr_qizHiby6b73SdovJAFnWCPDr0r8mmFoEWXjJmdLdw08daNzt8ww_r2khJ-rusUZVfiZY3kyRV1hfeChpNROGfmGbfN62KSsYBPi4dBMIGRz8SqkF6nw5nTC-HOr7Gd8mtZjG9KZYC5fKYOYRvbAZN_xyvLDzFUE6LgLmiT6fV7fHPQi5NSUfawpWQbIgK2sJjnp-ODTAijohyxQNuF4Lq1Prqzjt2QZRwvbskTcYM3gK5fgt6RYDN6MbARJIVFsb1Y7wZFg00dp2XhdFzwWoQl6BluvUL8bL73A8iJSam0csm1cuG0A7kMF9spy_whQw");
		iasToken = new SapIdToken(IOUtils.resourceToString("/iasOidcTokenRSA256.txt", UTF_8));

		mockConfiguration = Mockito.mock(OAuth2ServiceConfiguration.class);
		when(mockConfiguration.getService()).thenReturn(Service.IAS);
		when(mockConfiguration.getClientId()).thenReturn("client-id");

		tokenKeyServiceMock = Mockito.mock(OAuth2TokenKeyService.class);
		when(tokenKeyServiceMock
				.retrieveTokenKeys(any(), anyMap()))
						.thenReturn(IOUtils.resourceToString("/iasJsonWebTokenKeys.json", UTF_8));

		endpointsProviderMock = Mockito.mock(OAuth2ServiceEndpointsProvider.class);
		when(endpointsProviderMock.getJwksUri()).thenReturn(DUMMY_JKU_URI);

		oidcConfigServiceMock = Mockito.mock(OidcConfigurationService.class);
		when(oidcConfigServiceMock.retrieveEndpoints(any())).thenReturn(endpointsProviderMock);

		cut = new SapIdJwtSignatureValidator(
				mockConfiguration,
				OAuth2TokenKeyServiceWithCache.getInstance().withTokenKeyService(tokenKeyServiceMock),
				OidcConfigurationServiceWithCache.getInstance()
						.withOidcConfigurationService(oidcConfigServiceMock));
	}

	@Test
	public void validate_throwsWhenTokenIsNull() {
        Token tokenSpy = Mockito.spy(iasToken);
        doReturn(null).when(tokenSpy).getTokenValue();

		ValidationResult validationResult = cut.validate(tokenSpy);
		assertTrue(validationResult.isErroneous());
		assertThat(validationResult.getErrorDescription(), containsString("JWT token validation failed because token content was null."));
	}

	@Test
	public void validate_throwsWhenAlgorithmIsNull() {
        Token tokenSpy = Mockito.spy(iasToken);
        doReturn(null).when(tokenSpy).getHeaderParameterAsString(JsonWebKeyConstants.ALG_PARAMETER_NAME);

		ValidationResult validationResult = cut.validate(tokenSpy);
		assertTrue(validationResult.isErroneous());
		assertThat(validationResult.getErrorDescription(), containsString("JWT token validation with signature algorithm 'null' is not supported"));
	}

	@Test
	public void validate_throwsWhenKeyIdIsNull() {
        Token tokenSpy = Mockito.spy(iasToken);
        doReturn(null).when(tokenSpy).getHeaderParameterAsString(JsonWebKeyConstants.KID_PARAMETER_NAME);

		ValidationResult validationResult = cut.validate(tokenSpy);
		assertTrue(validationResult.isErroneous());
		assertThat(validationResult.getErrorDescription(), containsString("keyId must not be null"));
	}

	@Test
	public void validate_throwsWhenKeysUrlIsNull() {
		when(endpointsProviderMock.getJwksUri()).thenReturn(null);

		ValidationResult validationResult = cut.validate(iasToken);
		assertTrue(validationResult.isErroneous());
		assertThat(validationResult.getErrorDescription(), containsString("OIDC .well-known response did not contain JWKS URI."));
	}

	@Test
	public void validationFails_WhenAppTidIsNull() {
		ValidationResult validationResult = cut.validate(iasPaasToken);
		assertTrue(validationResult.isErroneous());
		assertThat(validationResult.getErrorDescription(),
				containsString("OIDC token must provide a valid app_tid header when issuer has a different domain than the url from the service credentials."));
	}

	@Test
	public void validate_whenAppTidIsNull_withDisabledAppTid() {
		SapIdJwtSignatureValidator cut = new SapIdJwtSignatureValidator(
				mockConfiguration,
				OAuth2TokenKeyServiceWithCache.getInstance()
						.withTokenKeyService(tokenKeyServiceMock),
				OidcConfigurationServiceWithCache.getInstance()
						.withOidcConfigurationService(oidcConfigServiceMock));
		cut.disableTenantIdCheck();
		assertTrue(cut.validate(iasPaasToken).isValid());
	}

	@Test
	public void validationFails_WhenZoneIdIsNull_ButIssuerMatchesOAuth2Url() {
		when(mockConfiguration.getUrl()).thenReturn(URI.create("https://application.myauth.com"));
		ValidationResult validationResult = cut.validate(iasPaasToken);
		assertTrue(validationResult.isValid());
	}

	@Test
	public void validate() {
		assertTrue(cut.validate(iasToken).isValid());
	}

	@Test
	public void validationFails_whenJwtPayloadModified() {
		String[] tokenHeaderPayloadSignature = iasToken.getTokenValue().split(Pattern.quote("."));
		String tokenWithOthersSignature = new StringBuilder("eyJhbGciOiJSUzI1NiJ9")
				.append(".")
				.append(tokenHeaderPayloadSignature[1])
				.append(".")
				.append(tokenHeaderPayloadSignature[2]).toString();
		assertThat(cut.validate(new SapIdToken(tokenWithOthersSignature)).isErroneous(), is(true));
	}

	@Test
	public void validationFails_whenJwtProvidesNoSignature() {
		String[] tokenHeaderPayloadSignature = iasToken.getTokenValue().split(Pattern.quote("."));
		String tokenWithNoSignature = new StringBuilder(tokenHeaderPayloadSignature[0])
				.append(".")
				.append(tokenHeaderPayloadSignature[1]).toString();
        Token tokenSpy = Mockito.spy(iasToken);
        doReturn(tokenWithNoSignature).when(tokenSpy).getTokenValue();

		ValidationResult result = cut.validate(tokenSpy);
		assertThat(result.isErroneous(), is(true));
		assertThat(result.getErrorDescription(), containsString("Jwt token does not consist of three sections: 'header'.'payload'.'signature'."));
	}

	@Test
	public void validationFails_whenTokenAlgorithmIsNotSupported() {
        Token tokenSpy = Mockito.spy(iasToken);
		String unsupportedAlgorithm = "UnsupportedAlgorithm";
		doReturn(unsupportedAlgorithm).when(tokenSpy).getHeaderParameterAsString(JsonWebKeyConstants.ALG_PARAMETER_NAME);

        ValidationResult validationResult = cut.validate(tokenSpy);

		assertThat(validationResult.isErroneous(), is(true));
		assertThat(validationResult.getErrorDescription(), startsWith("JWT token validation with signature algorithm '" + unsupportedAlgorithm + "' is not supported."));
	}

	@Test
	public void validationFails_whenTokenAlgorithmIsNone() {
        Token tokenSpy = Mockito.spy(iasToken);
        doReturn("NONE").when(tokenSpy).getHeaderParameterAsString(JsonWebKeyConstants.ALG_PARAMETER_NAME);

        ValidationResult validationResult = cut.validate(tokenSpy);

        assertThat(validationResult.isErroneous(), is(true));
        assertThat(validationResult.getErrorDescription(), startsWith("JWT token validation with signature algorithm 'NONE' is not supported."));
	}

	@Test
	public void validationFails_whenOAuthServerIsUnavailable() throws OAuth2ServiceException {
		when(tokenKeyServiceMock.retrieveTokenKeys(any(), anyMap())).thenThrow(OAuth2ServiceException.class);

		ValidationResult result = cut.validate(iasToken);
		assertThat(result.isErroneous(), is(true));
		assertThat(result.getErrorDescription(), containsString("JWKS could not be fetched"));
	}

}