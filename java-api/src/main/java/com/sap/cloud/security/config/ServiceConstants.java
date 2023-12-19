/**
 * SPDX-FileCopyrightText: 2018-2023 SAP SE or an SAP affiliate company and Cloud Security Client Java contributors
 * <p>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sap.cloud.security.config;

/**
 * Constants that simplifies access to service configuration properties.
 */
public class ServiceConstants {
	public static final String VCAP_SERVICES = "VCAP_SERVICES";
	public static final String VCAP_APPLICATION = "VCAP_APPLICATION";
	public static final String SERVICE_PLAN = "plan";
	public static final String NAME = "name";
	public static final String URL = "url";
	public static final String CLIENT_ID = "clientid";
	public static final String CLIENT_SECRET = "clientsecret";
	public static final String CERTIFICATE = "certificate";
	public static final String KEY = "key";

	private ServiceConstants() {
	}

	/**
	 * Constants that are specific to the Xsuaa identity service.
	 */
	public static class XSUAA {
		private XSUAA() {
		}

		public static final String IDENTITY_ZONE = "identityzone";
		public static final String API_URL = "apiurl";
		public static final String SUBACCOUNT_ID = "subaccountid";
		public static final String TENANT_ID = "tenantid";
		public static final String UAA_DOMAIN = "uaadomain";
		public static final String APP_ID = "xsappname";
		public static final String VERIFICATION_KEY = "verificationkey";
		public static final String CERT_URL = "certurl";
		public static final String CREDENTIAL_TYPE = "credential-type";

	}

	/**
	 * Constants that are specific to the Ias identity service.
	 */
	public static class IAS {
		private IAS() {
		}

		public static final String DOMAINS = "domains";
	}

	/**
	 * Represents service plans of identity services.
	 */
	public enum Plan {
		DEFAULT, BROKER, APPLICATION, SPACE, APIACCESS, SYSTEM;

		public static Plan from(String planAsString) {
			if (planAsString == null) {
				return APPLICATION;
			}
			return Plan.valueOf(planAsString.toUpperCase());
		}

		@Override
		public String toString() {
			return super.toString().toLowerCase();
		}
	}
}
