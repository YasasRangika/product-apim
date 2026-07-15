/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.am.integration.tests.restapi.admin;

import org.apache.http.HttpStatus;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;
import org.wso2.am.integration.clients.admin.ApiException;
import org.wso2.am.integration.clients.admin.ApiResponse;
import org.wso2.am.integration.clients.admin.api.dto.EnvironmentDTO;
import org.wso2.am.integration.tests.restapi.utils.PlatformGatewayTestUtils;
import org.wso2.am.integration.tests.websocket.client.WebSocketClientImpl;
import org.wso2.am.integration.test.utils.base.APIMIntegrationBaseTest;
import org.wso2.am.integration.test.utils.base.APIMIntegrationConstants;
import org.wso2.am.integration.test.utils.bean.APIRequest;
import org.wso2.am.integration.test.utils.bean.APIRevisionDeployUndeployRequest;
import org.wso2.am.integration.test.utils.bean.APIRevisionRequest;
import org.wso2.am.integration.test.utils.http.HTTPSClientUtils;
import org.wso2.carbon.automation.engine.annotations.ExecutionEnvironment;
import org.wso2.carbon.automation.engine.annotations.SetEnvironment;
import org.wso2.carbon.automation.engine.context.TestUserMode;
import org.wso2.carbon.automation.test.utils.http.client.HttpResponse;
import org.wso2.carbon.integration.common.utils.mgt.ServerConfigurationManager;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * P0 integration coverage for {@code [[apim.platform_gateway.connect]]} in {@code deployment.toml}
 * (carbon-apimgt#13882): TOML gateway absent before WebSocket bootstrap, lazy registration on connect,
 * internal {@code GET /deployments} after bootstrap (gateway-controller order), Publisher deploy
 * sync, and single-tenant org scoping ({@code carbon.super} vs {@code wso2.com}).
 */
@SetEnvironment(executionEnvironments = {ExecutionEnvironment.STANDALONE})
public class PlatformGatewayTomlConnectIntegrationTestCase extends APIMIntegrationBaseTest {

    private static final String GATEWAY_TYPE_PLATFORM = "APIPlatform";
    private static final String TENANT_DOMAIN = "wso2.com";
    private static final long DEPLOYMENT_SYNC_TIMEOUT_MS = 120_000L;
    private static final long POLL_INTERVAL_MS = 500L;

    /**
     * One TOML connect entry under test. Token row ids, gateway names, and gateway UUIDs must stay
     * in sync with {@code configFiles/platformGatewayConnect/deployment.toml}.
     */
    private static final class ConnectProfile {
        private final String connectTokenId;
        private final String connectPlainToken;
        private final String gatewayName;
        private final String expectedGatewayId;
        private final String organization;
        /** Gateway UUID bootstrapped under the other org (for cross-tenant visibility checks). */
        private final String otherOrgGatewayId;

        private ConnectProfile(String connectTokenId, String connectPlainToken, String gatewayName,
                               String expectedGatewayId, String organization, String otherOrgGatewayId) {
            this.connectTokenId = connectTokenId;
            this.connectPlainToken = connectPlainToken;
            this.gatewayName = gatewayName;
            this.expectedGatewayId = expectedGatewayId;
            this.organization = organization;
            this.otherOrgGatewayId = otherOrgGatewayId;
        }

        private String registrationToken() {
            return connectTokenId + "." + connectPlainToken;
        }
    }

    private static final ConnectProfile SUPER_PROFILE = new ConnectProfile(
            "01900000-0000-7000-8000-000000000001",
            "TomlConnectIntegrationPlainToken",
            "toml-pgw-it",
            "9ef4900a-1d12-3fe0-9842-d94b3771d539",
            APIMIntegrationConstants.SUPER_TENANT_DOMAIN,
            "5ea33aac-facf-3cbf-ab4b-6caed82c366b");

    private static final ConnectProfile TENANT_PROFILE = new ConnectProfile(
            "01900000-0000-7000-8000-000000000002",
            "TomlConnectTenantPlainToken",
            "toml-pgw-wso2",
            "5ea33aac-facf-3cbf-ab4b-6caed82c366b",
            TENANT_DOMAIN,
            "9ef4900a-1d12-3fe0-9842-d94b3771d539");

    private ServerConfigurationManager serverConfigurationManager;
    private ConnectProfile profile;
    private String apiEndPointUrl;
    private volatile boolean gatewayBootstrapped;

    @Factory(dataProvider = "userModeDataProvider")
    public PlatformGatewayTomlConnectIntegrationTestCase(TestUserMode userMode) {
        this.userMode = userMode;
    }

    @DataProvider
    public static Object[][] userModeDataProvider() {
        return new Object[][]{
                new Object[]{TestUserMode.SUPER_TENANT_ADMIN},
        };
    }

    @BeforeClass(alwaysRun = true)
    public void setEnvironment() throws Exception {
        profile = resolveProfile(userMode);
        super.init(userMode);
        serverConfigurationManager = new ServerConfigurationManager(superTenantKeyManagerContext);
        serverConfigurationManager.applyConfiguration(new File(
                getAMResourceLocation() + File.separator + "configFiles" + File.separator + "platformGatewayConnect"
                        + File.separator + "deployment.toml"));
        apiEndPointUrl = backEndServerUrl.getWebAppURLHttp() + "jaxrs_basic/services/customers/customerservice/";
    }

    @AfterClass(alwaysRun = true)
    public void destroy() throws Exception {
        if (gatewayBootstrapped && profile != null) {
            try {
                restAPIAdmin.deletePlatformGateway(profile.expectedGatewayId);
            } catch (ApiException ignored) {
                // Gateway may already be removed.
            }
        }
        if (serverConfigurationManager != null) {
            serverConfigurationManager.restoreToLastConfiguration();
        }
    }

    private static ConnectProfile resolveProfile(TestUserMode mode) {
        if (TestUserMode.TENANT_ADMIN == mode) {
            return TENANT_PROFILE;
        }
        return SUPER_PROFILE;
    }

    private void awaitPlatformGatewayActive(boolean expectActive, long timeoutMs) throws Exception {
        PlatformGatewayTestUtils.awaitPlatformGatewayIsActive(
                restAPIAdmin, profile.expectedGatewayId, expectActive, timeoutMs, POLL_INTERVAL_MS);
    }

    private String findDeploymentIdForApi(String apiUuid) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("api-key", profile.registrationToken());
        HttpResponse res = HTTPSClientUtils.doGet(PlatformGatewayTestUtils.INTERNAL_DATA_V1 + "/deployments", headers);
        if (res.getResponseCode() != HttpStatus.SC_OK) {
            return null;
        }
        JSONObject json = new JSONObject(res.getData());
        JSONArray arr = json.optJSONArray("deployments");
        if (arr == null) {
            return null;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.getJSONObject(i);
            if (apiUuid.equals(d.optString("artifactId"))) {
                String id = d.optString("deploymentId");
                return id.isEmpty() ? null : id;
            }
        }
        return null;
    }

    private String awaitDeploymentIdForApi(String apiUuid, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String id = findDeploymentIdForApi(apiUuid);
            if (id != null) {
                return id;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        Assert.fail("Timed out waiting for internal /deployments to list API " + apiUuid);
        return null;
    }

    @Test
    public void testTomlGatewayNotRegisteredBeforeWebSocketBootstrap() throws Exception {
        PlatformGatewayTestUtils.assertEnvironmentNotFound(restAPIAdmin, profile.expectedGatewayId,
                "TOML gateway environment should not exist before WebSocket bootstrap for org "
                        + profile.organization);
        PlatformGatewayTestUtils.assertEnvironmentNotFound(restAPIAdmin, profile.otherOrgGatewayId,
                "Other organization's TOML gateway should not be visible before bootstrap");
    }

    @Test(dependsOnMethods = "testTomlGatewayNotRegisteredBeforeWebSocketBootstrap")
    public void testWebSocketConnectBootstrapsTomlGateway() throws Exception {
        WebSocketClient client = PlatformGatewayTestUtils.newInternalDataWebSocketClient();
        WebSocketClientImpl socket = new WebSocketClientImpl();
        client.start();
        try {
            URI wsUri = new URI("wss://localhost:9943/internal/data/v1" + PlatformGatewayTestUtils.WS_GATEWAY_CONNECT_PATH);
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setHeader("api-key", profile.registrationToken());
            Future<Session> future = client.connect(socket, wsUri, request);
            Session session = future.get(15, TimeUnit.SECONDS);
            Assert.assertTrue(session.isOpen(), "WebSocket session should open with TOML connect token");

            awaitPlatformGatewayActive(true, 15_000L);
            gatewayBootstrapped = true;

            ApiResponse<EnvironmentDTO> envRes = restAPIAdmin.getEnvironment(profile.expectedGatewayId);
            Assert.assertEquals(envRes.getStatusCode(), HttpStatus.SC_OK);
            EnvironmentDTO env = envRes.getData();
            Assert.assertEquals(env.getGatewayType(), GATEWAY_TYPE_PLATFORM);
            Assert.assertEquals(env.getName(), profile.gatewayName);
            Assert.assertEquals(env.getId(), profile.expectedGatewayId);

            PlatformGatewayTestUtils.assertEnvironmentNotFound(restAPIAdmin, profile.otherOrgGatewayId,
                    "Bootstrapped gateway must remain scoped to org " + profile.organization);

            // Gateway controller polls GET /deployments only after WebSocket connect + bootstrap.
            Map<String, String> headers = new HashMap<>();
            headers.put("api-key", profile.registrationToken());
            HttpResponse deployments = HTTPSClientUtils.doGet(
                    PlatformGatewayTestUtils.INTERNAL_DATA_V1 + "/deployments", headers);
            Assert.assertEquals(deployments.getResponseCode(), HttpStatus.SC_OK,
                    "Connect registration token should authenticate internal /deployments after WebSocket bootstrap");

            session.close();
            awaitPlatformGatewayActive(false, 15_000L);
        } finally {
            client.stop();
        }
    }

    @Test(dependsOnMethods = "testWebSocketConnectBootstrapsTomlGateway")
    public void testPublisherDeployToTomlGatewaySyncsInternalDeployments() throws Exception {
        String apiId = null;
        String revisionUUID = null;
        try {
            String suffix = String.valueOf(System.currentTimeMillis());
            APIRequest apiRequest = new APIRequest("TomlPgDeployAPI_" + suffix, "tomlpgdeploy" + suffix,
                    new URL(apiEndPointUrl));
            apiRequest.setVersion("1.0.0");
            apiRequest.setTiersCollection(APIMIntegrationConstants.API_TIER.UNLIMITED);
            apiRequest.setTier(APIMIntegrationConstants.API_TIER.UNLIMITED);

            HttpResponse addApiRes = restAPIPublisher.addAPI(apiRequest);
            Assert.assertEquals(addApiRes.getResponseCode(), HttpStatus.SC_CREATED, addApiRes.getData());
            apiId = addApiRes.getData();

            APIRevisionRequest revReq = new APIRevisionRequest();
            revReq.setApiUUID(apiId);
            revReq.setDescription("toml connect gateway deploy for " + profile.organization);
            HttpResponse revRes = restAPIPublisher.addAPIRevision(revReq);
            Assert.assertEquals(revRes.getResponseCode(), HttpStatus.SC_CREATED, revRes.getData());
            revisionUUID = new JSONObject(revRes.getData()).getString("id");

            List<APIRevisionDeployUndeployRequest> deployList = new ArrayList<>();
            APIRevisionDeployUndeployRequest d = new APIRevisionDeployUndeployRequest();
            d.setName(profile.gatewayName);
            d.setVhost("localhost");
            d.setDisplayOnDevportal(true);
            deployList.add(d);
            HttpResponse depRes = restAPIPublisher.deployAPIRevision(apiId, revisionUUID, deployList, "API");
            Assert.assertEquals(depRes.getResponseCode(), HttpStatus.SC_CREATED, depRes.getData());

            String deploymentId = awaitDeploymentIdForApi(apiId, DEPLOYMENT_SYNC_TIMEOUT_MS);
            Assert.assertNotNull(deploymentId);

            byte[] rawBatch = PlatformGatewayTestUtils.postFetchBatchRaw(profile.registrationToken(), deploymentId);
            byte[] tarBytes = PlatformGatewayTestUtils.unwrapFetchBatchToTar(rawBatch);
            Assert.assertTrue(PlatformGatewayTestUtils.containsAscii(tarBytes, "ustar"),
                    "fetch-batch should return a tar archive for TOML-bootstrapped gateway deployment");
        } finally {
            if (apiId != null && revisionUUID != null) {
                try {
                    List<APIRevisionDeployUndeployRequest> list = new ArrayList<>();
                    APIRevisionDeployUndeployRequest u = new APIRevisionDeployUndeployRequest();
                    u.setName(profile.gatewayName);
                    u.setVhost(null);
                    u.setDisplayOnDevportal(true);
                    list.add(u);
                    restAPIPublisher.undeployAPIRevision(apiId, revisionUUID, list);
                } catch (Exception ignored) {
                }
                try {
                    restAPIPublisher.deleteAPIRevision(apiId, revisionUUID);
                } catch (Exception ignored) {
                }
            }
            if (apiId != null) {
                try {
                    restAPIPublisher.deleteAPI(apiId);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
