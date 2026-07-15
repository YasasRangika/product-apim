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

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;
import org.wso2.am.integration.clients.admin.ApiException;
import org.wso2.am.integration.clients.admin.ApiResponse;
import org.wso2.am.integration.clients.admin.api.dto.CreatePlatformGatewayRequestDTO;
import org.wso2.am.integration.clients.admin.api.dto.CreatePlatformGatewayRequestPermissionsDTO;
import org.wso2.am.integration.clients.admin.api.dto.EnvironmentDTO;
import org.wso2.am.integration.clients.admin.api.dto.EnvironmentListDTO;
import org.wso2.am.integration.clients.admin.api.dto.GatewayListDTO;
import org.wso2.am.integration.clients.admin.api.dto.GatewayResponseWithTokenDTO;
import org.wso2.am.integration.clients.admin.api.dto.PlatformGatewayResponseDTO;
import org.wso2.am.integration.clients.admin.api.dto.UpdatePlatformGatewayRequestDTO;
import org.wso2.am.integration.tests.restapi.utils.PlatformGatewayTestUtils;
import org.wso2.am.integration.tests.websocket.client.WebSocketClientImpl;
import org.wso2.am.integration.test.impl.RestAPIAdminImpl;
import org.wso2.am.integration.test.utils.base.APIMIntegrationBaseTest;
import org.wso2.am.integration.test.utils.base.APIMIntegrationConstants;
import org.wso2.am.integration.clients.store.api.v1.dto.APIEndpointURLsDTO;
import org.wso2.am.integration.test.impl.RestAPIStoreImpl;
import org.wso2.am.integration.test.utils.bean.APIRequest;
import org.wso2.am.integration.test.utils.bean.APIRevisionDeployUndeployRequest;
import org.wso2.am.integration.test.utils.bean.APIRevisionRequest;
import org.wso2.am.integration.test.utils.http.HTTPSClientUtils;
import org.wso2.carbon.automation.engine.annotations.ExecutionEnvironment;
import org.wso2.carbon.automation.engine.annotations.SetEnvironment;
import org.wso2.carbon.automation.engine.context.TestUserMode;
import org.wso2.carbon.automation.test.utils.http.client.HttpResponse;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for platform (Universal) gateway admin APIs, environment surfacing,
 * WebSocket connect to internal data plane, and gateway-scoped internal REST (/deployments, /fetch-batch, since).
 * Also covers: unknown environment 404, platform gateways omitted from GET /environments list.
 */
@SetEnvironment(executionEnvironments = {ExecutionEnvironment.STANDALONE})
public class PlatformGatewayIntegrationTestCase extends APIMIntegrationBaseTest {

    private static final String GATEWAY_TYPE_PLATFORM = "APIPlatform";
    private static final String INTERNAL_GATEWAY_WELL_KNOWN = "https://localhost:9943/internal/gateway/.well-known";
    private static final String TENANT_DOMAIN = "wso2.com";
    private static final String PGW_PERM_SUBSCRIBER_USER = "pgwpermuser";
    private static final String PGW_PERM_SUBSCRIBER_PASSWORD = "pgwperm123";
    private static final String PGW_PERM_SUBSCRIBER_ROLE = "PgwPermSubscriberRole";
    private static final String[] PGW_PERM_SUBSCRIBER_ONLY_ROLES = {
            APIMIntegrationConstants.APIM_INTERNAL_ROLE.SUBSCRIBER,
            APIMIntegrationConstants.APIM_INTERNAL_ROLE.EVERYONE
    };
    private static final String[] PGW_PERM_SUBSCRIBER_ROLE_PERMISSIONS = {
            "/permission/admin/login",
            "/permission/admin/manage/api/subscriber"
    };
    private static final long CONNECT_POLL_INTERVAL_MS = 500L;

    private String lastCreatedGatewayId;
    private String lastRegistrationToken;

    @Factory(dataProvider = "userModeDataProvider")
    public PlatformGatewayIntegrationTestCase(TestUserMode userMode) {
        this.userMode = userMode;
    }

    @DataProvider
    public static Object[][] userModeDataProvider() {
        return new Object[][]{
                new Object[]{TestUserMode.SUPER_TENANT_ADMIN},
                new Object[]{TestUserMode.TENANT_ADMIN}
        };
    }

    @DataProvider(name = "invalidGatewayNames")
    public static Object[][] invalidGatewayNames() {
        return new Object[][]{
                new Object[]{""},
                new Object[]{"ab"},
                new Object[]{"a".repeat(65)},
                new Object[]{"Invalid_Name"},
                new Object[]{"bad name"},
                new Object[]{"gateway!@#"},
        };
    }

    @BeforeClass(alwaysRun = true)
    public void setEnvironment() throws Exception {
        super.init(userMode);
        if (userMode == TestUserMode.SUPER_TENANT_ADMIN) {
            setupPermissionTestUsers();
        }
    }

    private void setupPermissionTestUsers() throws Exception {
        try {
            userManagementClient.addUser(PGW_PERM_SUBSCRIBER_USER, PGW_PERM_SUBSCRIBER_PASSWORD,
                    PGW_PERM_SUBSCRIBER_ONLY_ROLES, PGW_PERM_SUBSCRIBER_USER);
        } catch (Exception ignored) {
            // User may already exist from a prior run.
        }
        userManagementClient.addRole(PGW_PERM_SUBSCRIBER_ROLE, new String[]{PGW_PERM_SUBSCRIBER_USER},
                PGW_PERM_SUBSCRIBER_ROLE_PERMISSIONS);
    }

    @AfterMethod(alwaysRun = true)
    public void cleanupLastGateway() {
        if (StringUtils.isBlank(lastCreatedGatewayId)) {
            return;
        }
        try {
            restAPIAdmin.deletePlatformGateway(lastCreatedGatewayId);
        } catch (ApiException ignored) {
            // Gateway may already be removed by the test.
        } finally {
            lastCreatedGatewayId = null;
            lastRegistrationToken = null;
        }
    }

    private String uniqueGatewayName() {
        return "igw-" + System.currentTimeMillis();
    }

    private CreatePlatformGatewayRequestDTO newCreateRequest(String name) {
        CreatePlatformGatewayRequestDTO dto = new CreatePlatformGatewayRequestDTO();
        dto.setName(name);
        dto.setDisplayName("Integration test gateway");
        dto.setDescription("Created by PlatformGatewayIntegrationTestCase");
        dto.setVhost(java.net.URI.create("https://localhost:9999"));
        return dto;
    }

    private UpdatePlatformGatewayRequestDTO newUpdateRequest(String name, String displayName, String description) {
        UpdatePlatformGatewayRequestDTO update = new UpdatePlatformGatewayRequestDTO();
        update.setName(name);
        update.setVhost(java.net.URI.create("https://localhost:9999"));
        update.setDisplayName(displayName);
        update.setDescription(description);
        return update;
    }

    private void registerForCleanup(String gatewayId, String token) {
        this.lastCreatedGatewayId = gatewayId;
        this.lastRegistrationToken = token;
    }

    private RestAPIAdminImpl otherTenantAdminClient() {
        if (userMode == TestUserMode.TENANT_ADMIN) {
            return new RestAPIAdminImpl(RestAPIAdminImpl.username, RestAPIAdminImpl.password,
                    APIMIntegrationConstants.SUPER_TENANT_DOMAIN, adminURLHttps);
        }
        return new RestAPIAdminImpl(RestAPIAdminImpl.username, RestAPIAdminImpl.password,
                TENANT_DOMAIN, adminURLHttps);
    }

    private static boolean endpointUrlsContainGatewayName(List<APIEndpointURLsDTO> endpointURLs,
                                                          String gatewayName) {
        if (endpointURLs == null) {
            return false;
        }
        for (APIEndpointURLsDTO endpoint : endpointURLs) {
            if (gatewayName.equals(endpoint.getEnvironmentName())) {
                return true;
            }
        }
        return false;
    }

    private EnvironmentDTO findUniversalEnv(String gatewayId) throws ApiException {
        try {
            ApiResponse<EnvironmentDTO> res = restAPIAdmin.getEnvironment(gatewayId);
            if (res.getStatusCode() != HttpStatus.SC_OK || res.getData() == null) {
                return null;
            }
            EnvironmentDTO e = res.getData();
            if (GATEWAY_TYPE_PLATFORM.equals(e.getGatewayType()) && gatewayId.equals(e.getId())) {
                return e;
            }
            return null;
        } catch (ApiException e) {
            if (e.getCode() == HttpStatus.SC_NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    @Test
    public void testPlatformGatewayCreateAndList() throws Exception {
        String name = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(name));
        Assert.assertEquals(created.getStatusCode(), HttpStatus.SC_CREATED);
        GatewayResponseWithTokenDTO body = created.getData();
        Assert.assertNotNull(body.getId());
        Assert.assertTrue(StringUtils.isNotBlank(body.getRegistrationToken()));
        Assert.assertEquals(body.getName(), name);
        registerForCleanup(body.getId(), body.getRegistrationToken());

        ApiResponse<GatewayListDTO> listed = restAPIAdmin.getPlatformGateways();
        Assert.assertEquals(listed.getStatusCode(), HttpStatus.SC_OK);
        boolean seen = false;
        if (listed.getData().getList() != null) {
            for (PlatformGatewayResponseDTO g : listed.getData().getList()) {
                if (name.equals(g.getName())) {
                    seen = true;
                    break;
                }
            }
        }
        Assert.assertTrue(seen, "Created gateway should appear in GET /gateways list");
    }

    @Test
    public void testPlatformGatewayUpdate() throws Exception {
        String name = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(name));
        Assert.assertEquals(created.getStatusCode(), HttpStatus.SC_CREATED);
        registerForCleanup(created.getData().getId(), created.getData().getRegistrationToken());

        ApiResponse<PlatformGatewayResponseDTO> put = restAPIAdmin.updatePlatformGateway(
                created.getData().getId(), newUpdateRequest(name, "Updated display", "updated"));
        Assert.assertEquals(put.getStatusCode(), HttpStatus.SC_OK);
        Assert.assertEquals(put.getData().getDisplayName(), "Updated display");
    }

    @Test
    public void testPlatformGatewayRegenerateTokenRejectsOldTokenOnRest() throws Exception {
        String name = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(name));
        Assert.assertEquals(created.getStatusCode(), HttpStatus.SC_CREATED);
        registerForCleanup(created.getData().getId(), created.getData().getRegistrationToken());

        String oldToken = created.getData().getRegistrationToken();
        ApiResponse<GatewayResponseWithTokenDTO> regen =
                restAPIAdmin.regeneratePlatformGatewayToken(created.getData().getId());
        Assert.assertEquals(regen.getStatusCode(), HttpStatus.SC_OK);
        Assert.assertTrue(StringUtils.isNotBlank(regen.getData().getRegistrationToken()));
        Assert.assertNotEquals(regen.getData().getRegistrationToken(), oldToken);
        lastRegistrationToken = regen.getData().getRegistrationToken();

        Map<String, String> oldHeaders = new HashMap<>();
        oldHeaders.put("api-key", oldToken);
        HttpResponse oldDep = HTTPSClientUtils.doGet(
                PlatformGatewayTestUtils.INTERNAL_DATA_V1 + "/deployments", oldHeaders);
        Assert.assertEquals(oldDep.getResponseCode(), HttpStatus.SC_UNAUTHORIZED,
                "Revoked registration token should not authenticate internal /deployments");
    }

    @Test
    public void testPlatformGatewayDeleteThenNotFound() throws Exception {
        String name = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(name));
        Assert.assertEquals(created.getStatusCode(), HttpStatus.SC_CREATED);
        String gatewayId = created.getData().getId();

        restAPIAdmin.deletePlatformGateway(gatewayId);

        try {
            restAPIAdmin.deletePlatformGateway(gatewayId);
            Assert.fail("Expected 404 when deleting removed gateway");
        } catch (ApiException e) {
            Assert.assertEquals(e.getCode(), HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    public void testDuplicatePlatformGatewayNameReturnsConflict() throws Exception {
        String name = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> first = restAPIAdmin.createPlatformGateway(newCreateRequest(name));
        Assert.assertEquals(first.getStatusCode(), HttpStatus.SC_CREATED);
        registerForCleanup(first.getData().getId(), first.getData().getRegistrationToken());
        try {
            restAPIAdmin.createPlatformGateway(newCreateRequest(name));
            Assert.fail("Expected conflict for duplicate gateway name");
        } catch (ApiException e) {
            Assert.assertEquals(e.getCode(), HttpStatus.SC_CONFLICT);
        }
    }

    @Test(dataProvider = "invalidGatewayNames")
    public void testInvalidPlatformGatewayNameReturnsBadRequest(String invalidName) throws Exception {
        CreatePlatformGatewayRequestDTO dto = newCreateRequest(StringUtils.defaultString(invalidName));
        try {
            restAPIAdmin.createPlatformGateway(dto);
            Assert.fail("Expected validation error for invalid gateway name: [" + invalidName + "]");
        } catch (ApiException e) {
            Assert.assertEquals(e.getCode(), HttpStatus.SC_BAD_REQUEST);
        }
    }

    @Test
    public void testUpdatePlatformGatewayForUnknownIdReturnsNotFound() throws Exception {
        UpdatePlatformGatewayRequestDTO update = newUpdateRequest("missing-gw", "Display", "desc");
        try {
            restAPIAdmin.updatePlatformGateway(UUID.randomUUID().toString(), update);
            Assert.fail("Expected 404 when updating non-existent platform gateway");
        } catch (ApiException e) {
            Assert.assertEquals(e.getCode(), HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    public void testRegenerateTokenForUnknownGatewayReturnsNotFound() throws Exception {
        try {
            restAPIAdmin.regeneratePlatformGatewayToken(UUID.randomUUID().toString());
            Assert.fail("Expected 404 when regenerating token for non-existent platform gateway");
        } catch (ApiException e) {
            Assert.assertEquals(e.getCode(), HttpStatus.SC_NOT_FOUND);
        }
    }

    /**
     * PUT keeps gateway {@code name} immutable ({@code GatewaysApiServiceImpl#updatePlatformGateway}
     * rejects a body name that does not match the existing gateway).
     */
    @Test
    public void testUpdatePlatformGatewayWithMismatchedNameReturnsBadRequest() throws Exception {
        String firstName = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> first =
                restAPIAdmin.createPlatformGateway(newCreateRequest(firstName));
        Assert.assertEquals(first.getStatusCode(), HttpStatus.SC_CREATED);

        String secondName = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> second =
                restAPIAdmin.createPlatformGateway(newCreateRequest(secondName));
        Assert.assertEquals(second.getStatusCode(), HttpStatus.SC_CREATED);
        registerForCleanup(second.getData().getId(), second.getData().getRegistrationToken());

        try {
            restAPIAdmin.updatePlatformGateway(second.getData().getId(),
                    newUpdateRequest(firstName, "Updated display", "updated"));
            Assert.fail("Expected 400 when PUT body name does not match existing gateway");
        } catch (ApiException e) {
            Assert.assertEquals(e.getCode(), HttpStatus.SC_BAD_REQUEST);
        } finally {
            try {
                restAPIAdmin.deletePlatformGateway(first.getData().getId());
            } catch (ApiException ignored) {
            }
        }
    }

    @Test
    public void testUpdatePlatformGatewayWithInvalidVhostReturnsBadRequest() throws Exception {
        String name = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(name));
        Assert.assertEquals(created.getStatusCode(), HttpStatus.SC_CREATED);
        registerForCleanup(created.getData().getId(), created.getData().getRegistrationToken());

        UpdatePlatformGatewayRequestDTO update = newUpdateRequest(name, "Display", "desc");
        update.setVhost(java.net.URI.create("not-a-valid-url"));
        try {
            restAPIAdmin.updatePlatformGateway(created.getData().getId(), update);
            Assert.fail("Expected 400 for invalid vhost on update");
        } catch (ApiException e) {
            Assert.assertEquals(e.getCode(), HttpStatus.SC_BAD_REQUEST);
        }
    }

    @Test
    public void testPlatformGatewayDenyPermissionsHideGatewayFromSubscriberDevPortal() throws Exception {
        if (userMode != TestUserMode.SUPER_TENANT_ADMIN) {
            return;
        }

        String gatewayName = uniqueGatewayName();
        String gatewayId = null;
        String apiId = null;
        String revisionUUID = null;
        try {
            CreatePlatformGatewayRequestDTO dto = newCreateRequest(gatewayName);
            CreatePlatformGatewayRequestPermissionsDTO permissions = new CreatePlatformGatewayRequestPermissionsDTO();
            permissions.setPermissionType(CreatePlatformGatewayRequestPermissionsDTO.PermissionTypeEnum.DENY);
            permissions.setRoles(Collections.singletonList(PGW_PERM_SUBSCRIBER_ROLE));
            dto.setPermissions(permissions);

            ApiResponse<GatewayResponseWithTokenDTO> created = restAPIAdmin.createPlatformGateway(dto);
            Assert.assertEquals(created.getStatusCode(), HttpStatus.SC_CREATED);
            gatewayId = created.getData().getId();

            String apiEndPointUrl = backEndServerUrl.getWebAppURLHttp()
                    + "jaxrs_basic/services/customers/customerservice/";
            String suffix = String.valueOf(System.currentTimeMillis());
            APIRequest apiRequest = new APIRequest("PgDenyPermAPI_" + suffix, "pgdenyperm" + suffix,
                    new URL(apiEndPointUrl));
            apiRequest.setVersion("1.0.0");
            apiRequest.setTiersCollection(APIMIntegrationConstants.API_TIER.UNLIMITED);
            apiRequest.setTier(APIMIntegrationConstants.API_TIER.UNLIMITED);

            HttpResponse addApiRes = restAPIPublisher.addAPI(apiRequest);
            Assert.assertEquals(addApiRes.getResponseCode(), HttpStatus.SC_CREATED, addApiRes.getData());
            apiId = addApiRes.getData();

            HttpResponse publishRes = restAPIPublisher.changeAPILifeCycleStatusToPublish(apiId, false);
            Assert.assertEquals(publishRes.getResponseCode(), HttpStatus.SC_OK);

            APIRevisionRequest revReq = new APIRevisionRequest();
            revReq.setApiUUID(apiId);
            revReq.setDescription("platform gateway deny permission enforcement");
            HttpResponse revRes = restAPIPublisher.addAPIRevision(revReq);
            Assert.assertEquals(revRes.getResponseCode(), HttpStatus.SC_CREATED, revRes.getData());
            revisionUUID = new JSONObject(revRes.getData()).getString("id");

            List<APIRevisionDeployUndeployRequest> deployList = new ArrayList<>();
            APIRevisionDeployUndeployRequest deploy = new APIRevisionDeployUndeployRequest();
            deploy.setName(gatewayName);
            deploy.setVhost("localhost");
            deploy.setDisplayOnDevportal(true);
            deployList.add(deploy);
            HttpResponse depRes = restAPIPublisher.deployAPIRevision(apiId, revisionUUID, deployList, "API");
            Assert.assertEquals(depRes.getResponseCode(), HttpStatus.SC_CREATED, depRes.getData());

            RestAPIStoreImpl subscriberStore = new RestAPIStoreImpl(PGW_PERM_SUBSCRIBER_USER, PGW_PERM_SUBSCRIBER_PASSWORD,
                    APIMIntegrationConstants.SUPER_TENANT_DOMAIN, storeURLHttps);
            org.wso2.am.integration.clients.store.api.v1.dto.APIDTO apiDto = subscriberStore.getAPI(apiId);
            Assert.assertFalse(endpointUrlsContainGatewayName(apiDto.getEndpointURLs(), gatewayName),
                    "Subscriber with DENY-restricted role must not see platform gateway in dev portal endpoint URLs");
        } finally {
            if (apiId != null && revisionUUID != null) {
                try {
                    List<APIRevisionDeployUndeployRequest> undeployList = new ArrayList<>();
                    APIRevisionDeployUndeployRequest undeploy = new APIRevisionDeployUndeployRequest();
                    undeploy.setName(gatewayName);
                    undeploy.setVhost("localhost");
                    undeploy.setDisplayOnDevportal(true);
                    undeployList.add(undeploy);
                    restAPIPublisher.undeployAPIRevision(apiId, revisionUUID, undeployList);
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
            if (gatewayId != null) {
                try {
                    restAPIAdmin.deletePlatformGateway(gatewayId);
                } catch (ApiException ignored) {
                }
            }
        }
    }

    @Test
    public void testPlatformGatewayAllowPermissionsVisibleOnlyToAllowedRole() throws Exception {
        if (userMode != TestUserMode.SUPER_TENANT_ADMIN) {
            return;
        }

        String gatewayName = uniqueGatewayName();
        String gatewayId = null;
        String apiId = null;
        String revisionUUID = null;
        try {
            CreatePlatformGatewayRequestDTO dto = newCreateRequest(gatewayName);
            CreatePlatformGatewayRequestPermissionsDTO permissions = new CreatePlatformGatewayRequestPermissionsDTO();
            permissions.setPermissionType(CreatePlatformGatewayRequestPermissionsDTO.PermissionTypeEnum.ALLOW);
            permissions.setRoles(Collections.singletonList(APIMIntegrationConstants.APIM_INTERNAL_ROLE.PUBLISHER));
            dto.setPermissions(permissions);

            ApiResponse<GatewayResponseWithTokenDTO> created = restAPIAdmin.createPlatformGateway(dto);
            Assert.assertEquals(created.getStatusCode(), HttpStatus.SC_CREATED);
            gatewayId = created.getData().getId();

            String apiEndPointUrl = backEndServerUrl.getWebAppURLHttp()
                    + "jaxrs_basic/services/customers/customerservice/";
            String suffix = String.valueOf(System.currentTimeMillis());
            APIRequest apiRequest = new APIRequest("PgAllowPermAPI_" + suffix, "pgallowperm" + suffix,
                    new URL(apiEndPointUrl));
            apiRequest.setVersion("1.0.0");
            apiRequest.setTiersCollection(APIMIntegrationConstants.API_TIER.UNLIMITED);
            apiRequest.setTier(APIMIntegrationConstants.API_TIER.UNLIMITED);

            HttpResponse addApiRes = restAPIPublisher.addAPI(apiRequest);
            Assert.assertEquals(addApiRes.getResponseCode(), HttpStatus.SC_CREATED, addApiRes.getData());
            apiId = addApiRes.getData();

            HttpResponse publishRes = restAPIPublisher.changeAPILifeCycleStatusToPublish(apiId, false);
            Assert.assertEquals(publishRes.getResponseCode(), HttpStatus.SC_OK);

            APIRevisionRequest revReq = new APIRevisionRequest();
            revReq.setApiUUID(apiId);
            revReq.setDescription("platform gateway allow permission enforcement");
            HttpResponse revRes = restAPIPublisher.addAPIRevision(revReq);
            Assert.assertEquals(revRes.getResponseCode(), HttpStatus.SC_CREATED, revRes.getData());
            revisionUUID = new JSONObject(revRes.getData()).getString("id");

            List<APIRevisionDeployUndeployRequest> deployList = new ArrayList<>();
            APIRevisionDeployUndeployRequest deploy = new APIRevisionDeployUndeployRequest();
            deploy.setName(gatewayName);
            deploy.setVhost("localhost");
            deploy.setDisplayOnDevportal(true);
            deployList.add(deploy);
            HttpResponse depRes = restAPIPublisher.deployAPIRevision(apiId, revisionUUID, deployList, "API");
            Assert.assertEquals(depRes.getResponseCode(), HttpStatus.SC_CREATED, depRes.getData());

            RestAPIStoreImpl subscriberStore = new RestAPIStoreImpl(PGW_PERM_SUBSCRIBER_USER, PGW_PERM_SUBSCRIBER_PASSWORD,
                    APIMIntegrationConstants.SUPER_TENANT_DOMAIN, storeURLHttps);
            org.wso2.am.integration.clients.store.api.v1.dto.APIDTO subscriberView = subscriberStore.getAPI(apiId);
            Assert.assertFalse(endpointUrlsContainGatewayName(subscriberView.getEndpointURLs(), gatewayName),
                    "Subscriber without publisher role must not see ALLOW-restricted platform gateway");

            org.wso2.am.integration.clients.store.api.v1.dto.APIDTO publisherView = restAPIStore.getAPI(apiId);
            Assert.assertTrue(endpointUrlsContainGatewayName(publisherView.getEndpointURLs(), gatewayName),
                    "User with Internal/publisher role should see ALLOW-restricted platform gateway");
        } finally {
            if (apiId != null && revisionUUID != null) {
                try {
                    List<APIRevisionDeployUndeployRequest> undeployList = new ArrayList<>();
                    APIRevisionDeployUndeployRequest undeploy = new APIRevisionDeployUndeployRequest();
                    undeploy.setName(gatewayName);
                    undeploy.setVhost("localhost");
                    undeploy.setDisplayOnDevportal(true);
                    undeployList.add(undeploy);
                    restAPIPublisher.undeployAPIRevision(apiId, revisionUUID, undeployList);
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
            if (gatewayId != null) {
                try {
                    restAPIAdmin.deletePlatformGateway(gatewayId);
                } catch (ApiException ignored) {
                }
            }
        }
    }

    @Test
    public void testCrudGatewayCrossTenantIsolation() throws Exception {
        String gatewayName = uniqueGatewayName();
        String gatewayId = null;
        RestAPIAdminImpl otherTenantAdmin = null;
        try {
            ApiResponse<GatewayResponseWithTokenDTO> created =
                    restAPIAdmin.createPlatformGateway(newCreateRequest(gatewayName));
            Assert.assertEquals(created.getStatusCode(), HttpStatus.SC_CREATED);
            gatewayId = created.getData().getId();

            otherTenantAdmin = otherTenantAdminClient();
            PlatformGatewayTestUtils.assertEnvironmentNotFound(otherTenantAdmin, gatewayId,
                    "Platform gateway must not be visible to the other tenant's admin API");
        } finally {
            if (gatewayId != null) {
                try {
                    restAPIAdmin.deletePlatformGateway(gatewayId);
                } catch (ApiException ignored) {
                }
                if (otherTenantAdmin != null) {
                    try {
                        otherTenantAdmin.deletePlatformGateway(gatewayId);
                    } catch (ApiException ignored) {
                    }
                }
            }
        }
    }

    @Test
    public void testUniversalEnvironmentListsGatewayInactiveBeforeConnect() throws Exception {
        String name = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(name));
        String gatewayId = created.getData().getId();
        registerForCleanup(gatewayId, created.getData().getRegistrationToken());

        EnvironmentDTO env = findUniversalEnv(gatewayId);
        Assert.assertNotNull(env, "Platform gateway should be retrievable as GET /environments/{gatewayId}");
        Assert.assertEquals(env.getGatewayType(), GATEWAY_TYPE_PLATFORM);
        Boolean active = PlatformGatewayTestUtils.readPlatformGatewayIsActive(restAPIAdmin, gatewayId);
        Assert.assertNotNull(active, "Created gateway should appear in GET /gateways");
        Assert.assertFalse(active, "Gateway should not be active before WebSocket connect (GET /gateways isActive)");
        Assert.assertTrue((env.getVhost() != null)
                        || (env.getVhosts() != null && !env.getVhosts().isEmpty()
                        && StringUtils.isNotBlank(env.getVhosts().get(0).getHost())),
                "Environment should expose gateway host via vhost or vhosts");
    }

    @Test
    public void testWebSocketConnectSetsUniversalEnvironmentActive() throws Exception {
        String name = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(name));
        String gatewayId = created.getData().getId();
        String token = created.getData().getRegistrationToken();
        registerForCleanup(gatewayId, token);

        WebSocketClient client = PlatformGatewayTestUtils.newInternalDataWebSocketClient();
        WebSocketClientImpl socket = new WebSocketClientImpl();
        client.start();
        try {
            URI wsUri = new URI("wss://localhost:9943/internal/data/v1" + PlatformGatewayTestUtils.WS_GATEWAY_CONNECT_PATH);
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setHeader("api-key", token);
            Future<Session> future = client.connect(socket, wsUri, request);
            Session session = future.get(15, TimeUnit.SECONDS);
            Assert.assertTrue(session.isOpen(), "WebSocket session should be open after successful connect");
            socket.getLatch().await(5L, TimeUnit.SECONDS);

            PlatformGatewayTestUtils.awaitPlatformGatewayIsActive(
                    restAPIAdmin, gatewayId, true, 15000L, CONNECT_POLL_INTERVAL_MS);

            session.close();
            PlatformGatewayTestUtils.awaitPlatformGatewayIsActive(
                    restAPIAdmin, gatewayId, false, 15000L, CONNECT_POLL_INTERVAL_MS);
        } finally {
            client.stop();
        }
    }

    @Test
    public void testWebSocketRejectedForInvalidRegistrationToken() throws Exception {
        PlatformGatewayTestUtils.assertWebSocketConnectRejectedForInvalidApiKey(
                "definitely-not-a-valid-platform-gateway-token", 5000L);
    }

    @Test
    public void testInternalDeploymentsGetWithValidApiKey() throws Exception {
        String name = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(name));
        registerForCleanup(created.getData().getId(), created.getData().getRegistrationToken());
        String token = created.getData().getRegistrationToken();

        Map<String, String> headers = new HashMap<>();
        headers.put("api-key", token);
        HttpResponse res = HTTPSClientUtils.doGet(
                PlatformGatewayTestUtils.INTERNAL_DATA_V1 + "/deployments", headers);
        Assert.assertEquals(res.getResponseCode(), HttpStatus.SC_OK);
        JSONObject json = new JSONObject(res.getData());
        Assert.assertTrue(json.has("deployments"), "Response should contain deployments array");
    }

    @Test
    public void testInternalDeploymentsGetWithoutApiKeyReturnsUnauthorized() throws Exception {
        HttpResponse res = HTTPSClientUtils.doGet(
                PlatformGatewayTestUtils.INTERNAL_DATA_V1 + "/deployments", Collections.emptyMap());
        Assert.assertEquals(res.getResponseCode(), HttpStatus.SC_UNAUTHORIZED);
    }

    @Test
    public void testInternalGatewayWellKnownReturnsDiscoveryPayload() throws Exception {
        HttpResponse res = HTTPSClientUtils.doGet(INTERNAL_GATEWAY_WELL_KNOWN, Collections.emptyMap());
        Assert.assertEquals(res.getResponseCode(), HttpStatus.SC_OK);

        JSONObject json = new JSONObject(res.getData());
        Assert.assertEquals(json.optString("gatewayPath"), "internal/data/v1",
                "Well-known should expose internal REST base path without /ws suffix");
        JSONObject controlPlane = json.optJSONObject("controlPlane");
        Assert.assertNotNull(controlPlane, "Well-known payload should include controlPlane metadata");
        Assert.assertEquals(controlPlane.optString("type"), "APIM");
        Assert.assertTrue(StringUtils.isNotBlank(controlPlane.optString("version")),
                "Well-known control plane version should be present");
    }

    @Test
    public void testGetEnvironmentByUnknownIdReturnsNotFound() throws Exception {
        try {
            restAPIAdmin.getEnvironment(UUID.randomUUID().toString());
            Assert.fail("Expected ApiException for non-existent environment id");
        } catch (ApiException e) {
            Assert.assertEquals(e.getCode(), HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    public void testEnvironmentsListExcludesRegisteredPlatformGatewayByName() throws Exception {
        String name = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(name));
        Assert.assertEquals(created.getStatusCode(), HttpStatus.SC_CREATED);
        registerForCleanup(created.getData().getId(), created.getData().getRegistrationToken());

        ApiResponse<EnvironmentListDTO> listRes = restAPIAdmin.getEnvironments();
        Assert.assertEquals(listRes.getStatusCode(), HttpStatus.SC_OK);
        if (listRes.getData() != null && listRes.getData().getList() != null) {
            for (EnvironmentDTO e : listRes.getData().getList()) {
                Assert.assertNotEquals(name, e.getName(),
                        "GET /environments must not expose synthetic platform gateway environments by name");
            }
        }
    }

    @Test
    public void testInternalDeploymentsGetWithSinceQueryAccepted() throws Exception {
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(uniqueGatewayName()));
        registerForCleanup(created.getData().getId(), created.getData().getRegistrationToken());
        String token = created.getData().getRegistrationToken();

        Map<String, String> headers = new HashMap<>();
        headers.put("api-key", token);
        HttpResponse res = HTTPSClientUtils.doGet(
                PlatformGatewayTestUtils.INTERNAL_DATA_V1 + "/deployments?since=1970-01-01T00:00:00Z", headers);
        Assert.assertEquals(res.getResponseCode(), HttpStatus.SC_OK,
                "GET /deployments should accept optional ISO8601 since filter");
        JSONObject json = new JSONObject(res.getData());
        Assert.assertTrue(json.has("deployments"));
    }

    @Test
    public void testInternalFetchBatchWithUnknownDeploymentIdsReturnsOk() throws Exception {
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(uniqueGatewayName()));
        registerForCleanup(created.getData().getId(), created.getData().getRegistrationToken());
        String token = created.getData().getRegistrationToken();

        Map<String, String> headers = new HashMap<>();
        headers.put("api-key", token);
        headers.put("Content-Type", "application/json");
        String payload =
                "{\"deploymentIds\":[\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\",\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\"]}";
        HttpResponse res = HTTPSClientUtils.doPost(
                PlatformGatewayTestUtils.INTERNAL_DATA_V1 + "/deployments/fetch-batch", headers, payload);
        Assert.assertEquals(res.getResponseCode(), HttpStatus.SC_OK,
                "fetch-batch skips unknown ids and still returns an archive envelope");
        Assert.assertNotNull(res.getData());
    }

    @Test
    public void testInternalDeploymentsFetchBatchWithEmptyList() throws Exception {
        String name = uniqueGatewayName();
        ApiResponse<GatewayResponseWithTokenDTO> created =
                restAPIAdmin.createPlatformGateway(newCreateRequest(name));
        registerForCleanup(created.getData().getId(), created.getData().getRegistrationToken());
        String token = created.getData().getRegistrationToken();

        Map<String, String> headers = new HashMap<>();
        headers.put("api-key", token);
        headers.put("Content-Type", "application/json");
        String payload = "{\"deploymentIds\":[]}";
        HttpResponse res = HTTPSClientUtils.doPost(
                PlatformGatewayTestUtils.INTERNAL_DATA_V1 + "/deployments/fetch-batch", headers, payload);
        Assert.assertEquals(res.getResponseCode(), HttpStatus.SC_BAD_REQUEST,
                "Empty deploymentIds should be rejected for fetch-batch");
    }
}
