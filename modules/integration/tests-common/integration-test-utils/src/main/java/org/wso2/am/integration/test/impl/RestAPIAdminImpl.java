/*
 * Copyright (c) 2019, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the"License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an"AS IS"BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.am.integration.test.impl;

import com.google.gson.Gson;
import org.apache.commons.lang.StringUtils;
import org.wso2.am.integration.clients.admin.ApiClient;
import org.wso2.am.integration.clients.admin.ApiException;
import org.wso2.am.integration.clients.admin.ApiResponse;
import org.wso2.am.integration.clients.admin.api.ApplicationPolicyCollectionApi;
import org.wso2.am.integration.clients.admin.api.ApplicationPolicyIndividualApi;
import org.wso2.am.integration.clients.admin.api.CustomRulesCollectionApi;
import org.wso2.am.integration.clients.admin.api.CustomRulesIndividualApi;
import org.wso2.am.integration.clients.admin.api.KeyManagerCollectionApi;
import org.wso2.am.integration.clients.admin.api.KeyManagerIndividualApi;
import org.wso2.am.integration.clients.admin.api.SettingsApi;

import org.wso2.am.integration.clients.admin.api.SubscriptionPolicyCollectionApi;
import org.wso2.am.integration.clients.admin.api.SubscriptionPolicyIndividualApi;
import org.wso2.am.integration.clients.admin.api.dto.ApplicationThrottlePolicyDTO;
import org.wso2.am.integration.clients.admin.api.dto.CustomRuleDTO;
import org.wso2.am.integration.clients.admin.api.dto.KeyManagerDTO;
import org.wso2.am.integration.clients.admin.api.dto.KeyManagerListDTO;
import org.wso2.am.integration.clients.admin.api.dto.SettingsDTO;
import org.wso2.am.integration.clients.admin.api.dto.SubscriptionThrottlePolicyDTO;
import org.wso2.am.integration.test.ClientAuthenticator;
import org.wso2.am.integration.test.Constants;
import org.wso2.am.integration.test.HttpResponse;

import org.wso2.am.integration.clients.admin.api.WorkflowCollectionApi;
import org.wso2.am.integration.clients.admin.api.WorkflowsIndividualApi;
import org.wso2.am.integration.clients.admin.api.dto.WorkflowDTO;
import org.wso2.am.integration.clients.admin.api.dto.WorkflowInfoDTO;
import org.wso2.am.integration.clients.admin.api.dto.WorkflowListDTO;


/**
 * This util class performs the actions related to APIDTOobjects.
 */
public class RestAPIAdminImpl {

    public ApiClient apiAdminClient = new ApiClient();
    private KeyManagerCollectionApi keyManagerCollectionApi = new KeyManagerCollectionApi();
    private KeyManagerIndividualApi keyManagerIndividualApi = new KeyManagerIndividualApi();
    public WorkflowCollectionApi workflowCollectionApi = new WorkflowCollectionApi();
    public WorkflowsIndividualApi workflowsIndividualApi = new WorkflowsIndividualApi();
    private SettingsApi settingsApi = new SettingsApi();
    private ApplicationPolicyIndividualApi applicationPolicyIndividualApi = new ApplicationPolicyIndividualApi();
    private ApplicationPolicyCollectionApi applicationPolicyCollectionApi = new ApplicationPolicyCollectionApi();
    private SubscriptionPolicyIndividualApi subscriptionPolicyIndividualApi = new SubscriptionPolicyIndividualApi();
    private SubscriptionPolicyCollectionApi subscriptionPolicyCollectionApi = new SubscriptionPolicyCollectionApi();
    private CustomRulesIndividualApi customRulesIndividualApi = new CustomRulesIndividualApi();
    private CustomRulesCollectionApi customRulesCollectionApi = new CustomRulesCollectionApi();
    public static final String appName = "Integration_Test_App_Admin";
    public static final String callBackURL = "test.com";
    public static final String tokenScope = "Production";
    public static final String appOwner = "admin";
    public static final String grantType = "password";
    public static final String username = "admin";
    public static final String password = "admin";
    public String tenantDomain;


    public RestAPIAdminImpl(String username, String password, String tenantDomain, String adminURl) {
        // token/DCR of Publisher node itself will be used
        String tokenURL = adminURl + "oauth2/token";
        String dcrURL = adminURl + "client-registration/v0.17/register";
        String scopeList = "openid  " +
                "apim:admin " +
                "apim:tier_view " +
                "apim:tier_manage " +
                "apim:bl_view " +
                "apim:bl_manage " +
                "apim:mediation_policy_view " +
                "apim:mediation_policy_create " +
                "apim:app_owner_change " +
                "apim:app_import_export " +
                "apim:api_import_export " +
                "apim:api_product_import_export " +
                "apim:label_manage " +
                "apim:label_read " +
                "apim:monetization_usage_publish " +
                "apim:api_workflow_approve " +
                "apim:bot_data " +
                "apim:tenantInfo " +
                "apim:tenant_theme_manage " +
                "apim:admin_operations " +
                "apim:admin_settings " +
                "apim:admin_alert_manage " +
                "apim:api_workflow_view " +
                "apim:api_workflow_approve" +
                "apim:admin_operation" +
                "apim:scope_manage";

        String accessToken = ClientAuthenticator
                .getAccessToken(scopeList,
                        appName, callBackURL, tokenScope, appOwner, grantType, dcrURL, username, password, tenantDomain,
                        tokenURL);

        apiAdminClient.addDefaultHeader("Authorization", "Bearer " + accessToken);
        apiAdminClient.setBasePath(adminURl + "api/am/admin/v1");
        apiAdminClient.setDebugging(true);
        apiAdminClient.setReadTimeout(600000);
        apiAdminClient.setConnectTimeout(600000);
        apiAdminClient.setWriteTimeout(600000);
        keyManagerCollectionApi.setApiClient(apiAdminClient);
        keyManagerIndividualApi.setApiClient(apiAdminClient);
        settingsApi.setApiClient(apiAdminClient);
        applicationPolicyIndividualApi.setApiClient(apiAdminClient);
        applicationPolicyCollectionApi.setApiClient(apiAdminClient);
        subscriptionPolicyIndividualApi.setApiClient(apiAdminClient);
        subscriptionPolicyCollectionApi.setApiClient(apiAdminClient);
        customRulesIndividualApi.setApiClient(apiAdminClient);
        customRulesCollectionApi.setApiClient(apiAdminClient);
        workflowCollectionApi.setApiClient(apiAdminClient);
        workflowsIndividualApi.setApiClient(apiAdminClient);
        this.tenantDomain = tenantDomain;
    }

    public ApiResponse<KeyManagerDTO> addKeyManager(KeyManagerDTO keyManagerDTO) throws ApiException {

        return keyManagerCollectionApi.keyManagersPostWithHttpInfo(keyManagerDTO);
    }

    public KeyManagerListDTO getKeyManagers() throws ApiException {

        return keyManagerCollectionApi.keyManagersGet();
    }

    public KeyManagerDTO getKeyManager(String uuid) throws ApiException {

        return keyManagerIndividualApi.keyManagersKeyManagerIdGet(uuid);
    }

    public KeyManagerDTO updateKeyManager(String uuid, KeyManagerDTO keyManagerDTO) throws ApiException {

        return keyManagerIndividualApi.keyManagersKeyManagerIdPut(uuid, keyManagerDTO);
    }

    public void deleteKeyManager(String uuid) throws ApiException {

        keyManagerIndividualApi.keyManagersKeyManagerIdDelete(uuid);
    }

    public SettingsDTO getSettings() throws ApiException {

        return settingsApi.settingsGet();
    }

    /**
     * This method is used to create an application throttling policy.
     *
     * @param applicationThrottlePolicyDTO Application throttling policy DTO.
     * @return API response returned by the API call.
     * @throws ApiException if an error occurs while creating the application throttling policy.
     */
    public ApiResponse<ApplicationThrottlePolicyDTO> addApplicationThrottlingPolicy(
            ApplicationThrottlePolicyDTO applicationThrottlePolicyDTO) throws ApiException {

        return applicationPolicyCollectionApi
                .throttlingPoliciesApplicationPostWithHttpInfo(applicationThrottlePolicyDTO, Constants.jsonContentType);
    }

    /**
     * This method is used to retrieve an application throttling policy.
     *
     * @return API response returned by the API call.
     * @throws ApiException if an error occurs while retrieving the application throttling policy.
     */
    public ApiResponse<ApplicationThrottlePolicyDTO> getApplicationThrottlingPolicy(String policyId)
            throws ApiException {

        return applicationPolicyIndividualApi
                .throttlingPoliciesApplicationPolicyIdGetWithHttpInfo(policyId, null, null);
    }

    /**
     * This method is used to update an application throttling policy.
     *
     * @param policyId                     Policy Id of the application throttling policy to be updated.
     * @param applicationThrottlePolicyDTO Application throttling policy DTO which contains the update content.
     * @return API response returned by the API call.
     * @throws ApiException if an error occurs while updating the application throttling policy.
     */
    public ApiResponse<ApplicationThrottlePolicyDTO> updateApplicationThrottlingPolicy(String policyId,
                                                                                       ApplicationThrottlePolicyDTO applicationThrottlePolicyDTO)
            throws ApiException {

        return applicationPolicyIndividualApi
                .throttlingPoliciesApplicationPolicyIdPutWithHttpInfo(policyId, applicationThrottlePolicyDTO,
                        Constants.jsonContentType, null, null);
    }

    /**
     * This method is used to delete an application throttling policy.
     *
     * @param policyId Policy Id of the application throttling policy to be deleted.
     * @return API response returned by the API call.
     * @throws ApiException if an error occurs while deleting the application throttling policy.
     */
    public ApiResponse<Void> deleteApplicationThrottlingPolicy(String policyId) throws ApiException {

        return applicationPolicyIndividualApi
                .throttlingPoliciesApplicationPolicyIdDeleteWithHttpInfo(policyId, null, null);
    }

    /**
     * This method is used to create a subscription throttling policy.
     *
     * @param subscriptionThrottlePolicyDTO subscription throttling policy DTO.
     * @return API response returned by the API call.
     * @throws ApiException Throws if an error occurred while creating a new subscription policy.
     */
    public ApiResponse<SubscriptionThrottlePolicyDTO> addSubscriptionThrottlingPolicy(
            SubscriptionThrottlePolicyDTO subscriptionThrottlePolicyDTO) throws ApiException {

        return subscriptionPolicyCollectionApi
                .throttlingPoliciesSubscriptionPostWithHttpInfo(subscriptionThrottlePolicyDTO,
                        Constants.jsonContentType);

    }

    /**
     * This method is used to retrieve a subscription throttling policy.
     *
     * @return API response returned by API call.
     * @throws ApiException if an error occurs while retrieving the subscription throttling policy.
     */
    public ApiResponse<SubscriptionThrottlePolicyDTO> getSubscriptionThrottlingPolicy(String policyId)
            throws ApiException {

        return subscriptionPolicyIndividualApi
                .throttlingPoliciesSubscriptionPolicyIdGetWithHttpInfo(policyId, null, null);
    }

    /**
     * This method is used to update a subscription throttling policy.
     *
     * @param policyId                      Policy Id of the subscription throttling policy to be updated.
     * @param subscriptionThrottlePolicyDTO Subscription throttling policy DTO which contains the updated content.
     * @return API response returned by API call.
     * @throws ApiException if an error occurs while updating the subscription throttling policy.
     */
    public ApiResponse<SubscriptionThrottlePolicyDTO> updateSubscriptionThrottlingPolicy(String policyId,
                                                                                         SubscriptionThrottlePolicyDTO subscriptionThrottlePolicyDTO)
            throws ApiException {

        return subscriptionPolicyIndividualApi
                .throttlingPoliciesSubscriptionPolicyIdPutWithHttpInfo(policyId, subscriptionThrottlePolicyDTO,
                        Constants.jsonContentType, null, null);
    }

    /**
     * This method is used to delete a subscription throttling policy.
     *
     * @param policyId subscription throttling policy Id.
     * @return API response returned by the API call.
     * @throws ApiException Throws if an error occurred while creating a new subscription policy.
     */
    public ApiResponse<Void> deleteSubscriptionThrottlingPolicy(String policyId) throws ApiException {

        return subscriptionPolicyIndividualApi
                .throttlingPoliciesSubscriptionPolicyIdDeleteWithHttpInfo(policyId, null, null);
    }

    /**
     * This method is used to create an custom throttling policy.
     *
     * @param customRuleDTO Custom throttling policy DTO.
     * @return API response returned by API call.
     * @throws ApiException if an error occurs while creating the custom throttling policy.
     */
    public ApiResponse<CustomRuleDTO> addCustomThrottlingPolicy(CustomRuleDTO customRuleDTO) throws ApiException {

        return customRulesCollectionApi
                .throttlingPoliciesCustomPostWithHttpInfo(customRuleDTO, Constants.jsonContentType);
    }

    /**
     * This method is used to retrieve a custom throttling policy.
     *
     * @return API response returned by API call.
     * @throws ApiException if an error occurs while retrieving the custom throttling policy.
     */
    public ApiResponse<CustomRuleDTO> getCustomThrottlingPolicy(String policyId) throws ApiException {

        return customRulesIndividualApi
                .throttlingPoliciesCustomRuleIdGetWithHttpInfo(policyId, null, null);
    }

    /**
     * This method is used to update a custom throttling policy.
     *
     * @param policyId      Policy Id of the custom throttling policy to be updated.
     * @param customRuleDTO Custom throttling policy DTO which contains the updated content.
     * @return API response returned by API call.
     * @throws ApiException if an error occurs while updating the custom throttling policy.
     */
    public ApiResponse<CustomRuleDTO> updateCustomThrottlingPolicy(String policyId, CustomRuleDTO customRuleDTO)
            throws ApiException {

        return customRulesIndividualApi
                .throttlingPoliciesCustomRuleIdPutWithHttpInfo(policyId, customRuleDTO,
                        Constants.jsonContentType, null, null);
    }

    /**
     * This method is used to delete a custom throttling policy.
     *
     * @param policyId Policy Id of the custom throttling policy to be deleted.
     * @return API response returned by API call.
     * @throws ApiException if an error occurs while deleting the custom throttling policy.
     */
    public ApiResponse<Void> deleteCustomThrottlingPolicy(String policyId) throws ApiException {

        return customRulesIndividualApi
                .throttlingPoliciesCustomRuleIdDeleteWithHttpInfo(policyId, null, null);
    }

    public HttpResponse getWorkflowByExternalWorkflowReference(String externalWorkflowRef) throws ApiException {
        WorkflowInfoDTO workflowInfodto = null;
        HttpResponse response = null;
        Gson gson = new Gson();
        try {
            workflowInfodto = workflowsIndividualApi.workflowsExternalWorkflowRefGet(externalWorkflowRef, null);
        } catch (ApiException e) {
            return new HttpResponse(gson.toJson(e.getResponseBody()), e.getCode());
        }
        if (StringUtils.isNotEmpty(workflowInfodto.getReferenceId())) {
            response = new HttpResponse(gson.toJson(workflowInfodto), 200);
        }
        return response;
    }

    public HttpResponse getWorkflows(String workflowType) throws ApiException {
        WorkflowListDTO workflowListdto = null;
        HttpResponse response = null;
        Gson gson = new Gson();
        try {
            workflowListdto = workflowCollectionApi.workflowsGet(null, null, null, null, workflowType);
            response = new HttpResponse(gson.toJson(workflowListdto), 200);
        } catch (ApiException e) {
            return new HttpResponse(gson.toJson(e.getResponseBody()), e.getCode());
        }
        return response;
    }

    public HttpResponse updateWorkflowStatus(String workflowReferenceId) throws ApiException {
        WorkflowDTO workflowdto = null;
        HttpResponse response = null;
        Gson gson = new Gson();

        WorkflowDTO body = new WorkflowDTO();
        WorkflowDTO.StatusEnum status = WorkflowDTO.StatusEnum.valueOf(WorkflowDTO.StatusEnum.class, "APPROVED");
        body.setStatus(status);
        body.setDescription("Approve workflow request.");
        //body.setAttributes();
        try {
            workflowdto = workflowsIndividualApi.workflowsUpdateWorkflowStatusPost(workflowReferenceId, body);
            response = new HttpResponse(gson.toJson(workflowdto), 200);
        } catch (ApiException e) {
            return new HttpResponse(gson.toJson(e.getResponseBody()), e.getCode());
        }
        return response;
    }
}
