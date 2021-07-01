/*
 *  Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.apim.monetization.impl.workflow;

import com.google.gson.Gson;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.wso2.apim.monetization.impl.ZarinpalMonetizationConstants;
import org.wso2.apim.monetization.impl.ZarinpalMonetizationDAO;
import org.wso2.apim.monetization.impl.ZarinpalMonetizationException;
import org.wso2.apim.monetization.impl.model.MonetizationPlatformCustomer;
import org.wso2.apim.monetization.impl.model.MonetizationSharedCustomer;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.WorkflowResponse;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.APIProduct;
import org.wso2.carbon.apimgt.api.model.Subscriber;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.SubscriptionWorkflowDTO;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.workflow.GeneralWorkflowResponse;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowConstants;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowExecutor;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowStatus;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * worrkflow executor for zarinpal based subscription create action
 */
public class ZarinpalSubscriptionCreationWorkflowExecutor extends WorkflowExecutor {

    private static final Log log = LogFactory.getLog(ZarinpalSubscriptionCreationWorkflowExecutor.class);
    ZarinpalMonetizationDAO zarinpalMonetizationDAO = ZarinpalMonetizationDAO.getInstance();

    @Override
    public String getWorkflowType() {
        return WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION;
    }

    @Override
    public List<WorkflowDTO> getWorkflowDetails(String workflowStatus) throws WorkflowException {
        return null;
    }

    /**
     * This method executes subscription creation workflow and return workflow response back to the caller
     *
     * @param workflowDTO The WorkflowDTO which contains workflow contextual information related to the workflow
     * @return workflow response back to the caller
     * @throws WorkflowException Thrown when the workflow execution was not fully performed
     */
    @Override
    public WorkflowResponse execute(WorkflowDTO workflowDTO) throws WorkflowException {

        SubscriptionWorkflowDTO subsWorkflowDTO = (SubscriptionWorkflowDTO) workflowDTO;
        workflowDTO.setProperties("apiName", subsWorkflowDTO.getApiName());
        workflowDTO.setProperties("apiVersion", subsWorkflowDTO.getApiVersion());
        workflowDTO.setProperties("subscriber", subsWorkflowDTO.getSubscriber());
        workflowDTO.setProperties("applicationName", subsWorkflowDTO.getApplicationName());
        super.execute(workflowDTO);
        workflowDTO.setStatus(WorkflowStatus.APPROVED);
        WorkflowResponse workflowResponse = complete(workflowDTO);
        super.publishEvents(workflowDTO);

        return workflowResponse;
    }

    /**
     * This method executes monetization related functions in the subscription creation workflow
     *
     * @param workflowDTO The WorkflowDTO which contains workflow contextual information related to the workflow
     * @param api         API
     * @return workflow response back to the caller
     * @throws WorkflowException Thrown when the workflow execution was not fully performed
     */
    @Override
    public WorkflowResponse monetizeSubscription(WorkflowDTO workflowDTO, API api) throws WorkflowException {

        boolean isMonetizationEnabled = false;
        SubscriptionWorkflowDTO subWorkFlowDTO = null;
        String zarinpalPlatformAccountKey = null;
        Subscriber subscriber = null;
        Customer customer = null;
        Customer sharedCustomerBE = null;
        MonetizationPlatformCustomer monetizationPlatformCustomer;
        MonetizationSharedCustomer monetizationSharedCustomer;
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        subWorkFlowDTO = (SubscriptionWorkflowDTO) workflowDTO;

        //read the platform account key of Zarinpal
        Zarinpal.apiKey = getPlatformAccountKey(subWorkFlowDTO.getTenantId());
        String connectedAccountKey = StringUtils.EMPTY;
        Map<String, String> monetizationProperties = new Gson().fromJson(api.getMonetizationProperties().toString(),
                HashMap.class);
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(ZarinpalMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            connectedAccountKey = monetizationProperties.get
                    (ZarinpalMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account zarinpal key was not found for API : "
                        + api.getId().getApiName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage);
            }
        } else {
            String errorMessage = "Zarinpal key of the connected account is empty.";
            log.error(errorMessage);
            throw new WorkflowException(errorMessage);
        }
        //needed to create artifacts in the zarinpal connected account
        RequestOptions requestOptions = RequestOptions.builder().setZarinpalAccount(connectedAccountKey).build();
        try {
            subscriber = apiMgtDAO.getSubscriber(subWorkFlowDTO.getSubscriber());
            // check whether the application is already registered as a customer under the particular
            // APIprovider/Connected Account in Zarinpal
            monetizationSharedCustomer = zarinpalMonetizationDAO.getSharedCustomer(subWorkFlowDTO.getApplicationId(),
                    subWorkFlowDTO.getApiProvider(), subWorkFlowDTO.getTenantId());
            if (monetizationSharedCustomer.getSharedCustomerId() == null) {
                // checks whether the subscriber is already registered as a customer Under the
                // tenant/Platform account in Zarinpal
                monetizationPlatformCustomer = zarinpalMonetizationDAO.getPlatformCustomer(subscriber.getId(),
                        subscriber.getTenantId());
                if (monetizationPlatformCustomer.getCustomerId() == null) {
                    monetizationPlatformCustomer = createMonetizationPlatformCutomer(subscriber);
                }
                monetizationSharedCustomer = createSharedCustomer(subscriber.getEmail(), monetizationPlatformCustomer,
                        requestOptions, subWorkFlowDTO);
            }
            //creating Subscriptions
            int apiId = ApiMgtDAO.getInstance().getAPIID(api.getId(), null);
            String planId = zarinpalMonetizationDAO.getBillingEnginePlanIdForTier(apiId, subWorkFlowDTO.getTierName());
            createMonetizedSubscriptions(planId, monetizationSharedCustomer, requestOptions, subWorkFlowDTO);
        } catch (APIManagementException e) {
            String errorMessage = "Could not monetize subscription for API : " + subWorkFlowDTO.getApiName()
                    + " by Application : " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        } catch (ZarinpalMonetizationException e) {
            String errorMessage = "Could not monetize subscription for API : " + subWorkFlowDTO.getApiName()
                    + " by Application " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        }
        return execute(workflowDTO);
    }

    @Override
    public WorkflowResponse monetizeSubscription(WorkflowDTO workflowDTO, APIProduct apiProduct)
            throws WorkflowException {

        boolean isMonetizationEnabled = false;
        SubscriptionWorkflowDTO subWorkFlowDTO = null;
        String zarinpalPlatformAccountKey = null;
        Subscriber subscriber = null;
        Customer customer = null;
        Customer sharedCustomerBE = null;
        MonetizationPlatformCustomer monetizationPlatformCustomer;
        MonetizationSharedCustomer monetizationSharedCustomer;
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        subWorkFlowDTO = (SubscriptionWorkflowDTO) workflowDTO;
        //read the platform account key of Zarinpal
        Zarinpal.apiKey = getPlatformAccountKey(subWorkFlowDTO.getTenantId());
        String connectedAccountKey = StringUtils.EMPTY;
        Map<String, String> monetizationProperties = new Gson().fromJson(apiProduct.getMonetizationProperties().toString(),
                HashMap.class);
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(ZarinpalMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            connectedAccountKey = monetizationProperties.get
                    (ZarinpalMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account zarinpal key was not found for : "
                        + apiProduct.getId().getName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage);
            }
        } else {
            String errorMessage = "Zarinpal key of the connected account is empty.";
            log.error(errorMessage);
            throw new WorkflowException(errorMessage);
        }
        //needed to create artifacts in the zarinpal connected account
        RequestOptions requestOptions = RequestOptions.builder().setZarinpalAccount(connectedAccountKey).build();
        try {
            subscriber = apiMgtDAO.getSubscriber(subWorkFlowDTO.getSubscriber());
            // check whether the application is already registered as a customer under the particular
            // APIprovider/Connected Account in Zarinpal
            monetizationSharedCustomer = zarinpalMonetizationDAO.getSharedCustomer(subWorkFlowDTO.getApplicationId(),
                    subWorkFlowDTO.getApiProvider(), subWorkFlowDTO.getTenantId());
            if (monetizationSharedCustomer.getSharedCustomerId() == null) {
                // checks whether the subscriber is already registered as a customer Under the
                // tenant/Platform account in Zarinpal
                monetizationPlatformCustomer = zarinpalMonetizationDAO.getPlatformCustomer(subscriber.getId(),
                        subscriber.getTenantId());
                if (monetizationPlatformCustomer.getCustomerId() == null) {
                    monetizationPlatformCustomer = createMonetizationPlatformCutomer(subscriber);
                }
                monetizationSharedCustomer = createSharedCustomer(subscriber.getEmail(), monetizationPlatformCustomer,
                        requestOptions, subWorkFlowDTO);
            }
            //creating Subscriptions
            int apiId = ApiMgtDAO.getInstance().getAPIProductId(apiProduct.getId());
            String planId = zarinpalMonetizationDAO.getBillingEnginePlanIdForTier(apiId, subWorkFlowDTO.getTierName());
            createMonetizedSubscriptions(planId, monetizationSharedCustomer, requestOptions, subWorkFlowDTO);
        } catch (APIManagementException e) {
            String errorMessage = "Could not monetize subscription for : " + subWorkFlowDTO.getApiName()
                    + " by application : " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        } catch (ZarinpalMonetizationException e) {
            String errorMessage = "Could not monetize subscription for : " + subWorkFlowDTO.getApiName()
                    + " by application " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        }
        return execute(workflowDTO);
    }

    /**
     * Returns the zarinpal key of the platform/tenant
     *
     * @param tenantId id of the tenant
     * @return the zarinpal key of the platform/tenant
     * @throws WorkflowException
     */
    private String getPlatformAccountKey(int tenantId) throws WorkflowException {

        String zarinpalPlatformAccountKey = null;
        try {
            Registry configRegistry = ServiceReferenceHolder.getInstance().getRegistryService().getConfigSystemRegistry(
                    tenantId);
            if (configRegistry.resourceExists(APIConstants.API_TENANT_CONF_LOCATION)) {
                Resource resource = configRegistry.get(APIConstants.API_TENANT_CONF_LOCATION);
                String content = new String((byte[]) resource.getContent(), Charset.defaultCharset());

                if (StringUtils.isBlank(content)) {
                    String errorMessage = "Tenant configuration cannot be empty when configuring monetization.";
                    throw new WorkflowException(errorMessage);
                }
                //get the zarinpal key of patform account from tenant conf file
                JSONObject tenantConfig = (JSONObject) new JSONParser().parse(content);
                JSONObject monetizationInfo = (JSONObject) tenantConfig.get(
                        ZarinpalMonetizationConstants.MONETIZATION_INFO);
                zarinpalPlatformAccountKey = monetizationInfo.get(
                        ZarinpalMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY).toString();

                if (StringUtils.isBlank(zarinpalPlatformAccountKey)) {
                    throw new WorkflowException("zarinpalPlatformAccountKey is empty!!!");
                }
            }
        } catch (RegistryException ex) {
            throw new WorkflowException("Could not get all registry objects : ", ex);
        } catch (org.json.simple.parser.ParseException ex) {
            throw new WorkflowException("Could not get Zarinpal Platform key : ", ex);
        }
        return zarinpalPlatformAccountKey;
    }

    /**
     * The method creates a Shared Customer in billing engine
     *
     * @param email            Email of the subscriber
     * @param platformCustomer Monetization customer details created under platform account
     * @param requestOptions   contains credentials to make api requests on behalf of the connected account
     * @param subWorkFlowDTO   The WorkflowDTO which contains workflow contextual information related to the workflow
     * @return MonetizationSharedCustomer Object with the details of the created shared customer
     * @throws WorkflowException
     */
    public MonetizationSharedCustomer createSharedCustomer(String email, MonetizationPlatformCustomer platformCustomer,
                                                           RequestOptions requestOptions,
                                                           SubscriptionWorkflowDTO subWorkFlowDTO)
            throws WorkflowException {

        Customer zarinpalCustomer;
        MonetizationSharedCustomer monetizationSharedCustomer = new MonetizationSharedCustomer();
        Token token;
        try {
            Map<String, Object> params = new HashMap<String, Object>();
            params.put(ZarinpalMonetizationConstants.CUSTOMER, platformCustomer.getCustomerId());
            //creating a token using the platform customers source
            token = Token.create(params, requestOptions);
        } catch (ZarinpalException ex) {
            String errorMsg = "Error when creating a zarinpal token for" + platformCustomer.getSubscriberName();
            log.error(errorMsg);
            throw new WorkflowException(errorMsg, ex);
        }
        Map<String, Object> sharedCustomerParams = new HashMap<>();
        //if the email id of subscriber is empty, a customer object in billing engine will be created without email id
        if (!StringUtils.isEmpty(email)) {
            sharedCustomerParams.put(ZarinpalMonetizationConstants.CUSTOMER_EMAIL, email);
        }
        try {
            sharedCustomerParams.put(ZarinpalMonetizationConstants.CUSTOMER_DESCRIPTION, "Shared Customer for "
                    + subWorkFlowDTO.getApplicationName() + ZarinpalMonetizationConstants.FILE_SEPERATOR
                    + subWorkFlowDTO.getSubscriber());
            sharedCustomerParams.put(ZarinpalMonetizationConstants.CUSTOMER_SOURCE, token.getId());
            zarinpalCustomer = Customer.create(sharedCustomerParams, requestOptions);
            try {
                monetizationSharedCustomer.setApplicationId(subWorkFlowDTO.getApplicationId());
                monetizationSharedCustomer.setApiProvider(subWorkFlowDTO.getApiProvider());
                monetizationSharedCustomer.setTenantId(subWorkFlowDTO.getTenantId());
                monetizationSharedCustomer.setSharedCustomerId(zarinpalCustomer.getId());
                monetizationSharedCustomer.setParentCustomerId(platformCustomer.getId());
                //returns the ID of the inserted record
                int id = zarinpalMonetizationDAO.addBESharedCustomer(monetizationSharedCustomer);
                monetizationSharedCustomer.setId(id);
            } catch (ZarinpalMonetizationException ex) {
                //deleting the created customer in zarinpal if it fails to create the DB record
                zarinpalCustomer.delete(requestOptions);
                String errorMsg = "Error when inserting Zarinpal shared customer details of Application : "
                        + subWorkFlowDTO.getApplicationName() + "to database";
                log.error(errorMsg, ex);
                throw new WorkflowException(errorMsg, ex);
            }
            if (log.isDebugEnabled()) {
                String msg = "A customer for Application " + subWorkFlowDTO.getApplicationName()
                        + " is created under the " + subWorkFlowDTO.getApiProvider()
                        + "'s connected account in Zarinpal";
                log.debug(msg);
            }
        } catch (ZarinpalException ex) {
            String errorMsg = "Error while creating a shared customer in Zarinpal for Application : "
                    + subWorkFlowDTO.getApplicationName();
            log.error(errorMsg);
            throw new WorkflowException(errorMsg, ex);
        }
        return monetizationSharedCustomer;
    }

    /**
     * The method creates a subscription in Billing Engine
     *
     * @param planId         plan Id of the Zarinpal monetization plan
     * @param sharedCustomer contains info about the customer created in the provider account of Zarinpal
     * @param requestOptions contains connected account credential needed for Zarinpal transactions
     * @param subWorkFlowDTO The WorkflowDTO which contains workflow contextual information related to the workflow
     * @throws WorkflowException
     */
    public void createMonetizedSubscriptions(String planId, MonetizationSharedCustomer sharedCustomer,
                                             RequestOptions requestOptions, SubscriptionWorkflowDTO subWorkFlowDTO)
            throws WorkflowException {

        ZarinpalMonetizationDAO zarinpalMonetizationDAO = ZarinpalMonetizationDAO.getInstance();
        APIIdentifier identifier = new APIIdentifier(subWorkFlowDTO.getApiProvider(), subWorkFlowDTO.getApiName(),
                subWorkFlowDTO.getApiVersion());
        Subscription subscription = null;
        try {
            Map<String, Object> item = new HashMap<String, Object>();
            item.put(ZarinpalMonetizationConstants.PLAN, planId);
            Map<String, Object> items = new HashMap<String, Object>();
            //adding a subscription item, with an attached plan.
            items.put("0", item);
            Map<String, Object> subParams = new HashMap<String, Object>();
            subParams.put(ZarinpalMonetizationConstants.CUSTOMER, sharedCustomer.getSharedCustomerId());
            subParams.put(ZarinpalMonetizationConstants.ITEMS, items);
            try {
                //create a subscritpion in zarinpal under the API Providers Connected Account
                subscription = Subscription.create(subParams, requestOptions);
            } catch (ZarinpalException ex) {
                String errorMsg = "Error when adding a subscription in Zarinpal for Application : " +
                        subWorkFlowDTO.getApplicationName();
                log.error(errorMsg);
                throw new WorkflowException(errorMsg, ex);
            }
            try {
                zarinpalMonetizationDAO.addBESubscription(identifier, subWorkFlowDTO.getApplicationId(),
                        subWorkFlowDTO.getTenantId(), sharedCustomer.getId(), subscription.getId());
            } catch (ZarinpalMonetizationException e) {
                //delete the subscription in Zarinpal, if the entry to database fails in API Manager
                subscription.cancel((Map<String, Object>) null, requestOptions);
                String errorMsg = "Error when adding zarinpal subscription details of Application "
                        + subWorkFlowDTO.getApplicationName() + " to Database";
                log.error(errorMsg);
                throw new WorkflowException(errorMsg, e);
            }
            if (log.isDebugEnabled()) {
                String msg = "Zarinpal subscription for " + subWorkFlowDTO.getApplicationName() + " is created for"
                        + subWorkFlowDTO.getApiName() + " API";
                log.debug(msg);
            }
        } catch (ZarinpalException ex) {
            String errorMessage = "Failed to create subscription in Zarinpal for API : " + subWorkFlowDTO.getApiName()
                    + "by Application : " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, ex);
        }
    }

    /**
     * The method creates a Platform Customer in Billing Engine
     *
     * @param subscriber object which contains info about the subscriber
     * @return ZarinpalCustomer object which contains info about the customer created in platform account of zarinpal
     * @throws WorkflowException
     */
    public MonetizationPlatformCustomer createMonetizationPlatformCutomer(Subscriber subscriber)
            throws WorkflowException {

        MonetizationPlatformCustomer monetizationPlatformCustomer = new MonetizationPlatformCustomer();
        Customer customer = null;
        try {
            Map<String, Object> customerParams = new HashMap<String, Object>();
            //Customer object in billing engine will be created without the email id
            if (!StringUtils.isEmpty(subscriber.getEmail())) {
                customerParams.put(ZarinpalMonetizationConstants.CUSTOMER_EMAIL, subscriber.getEmail());
            }
            customerParams.put(ZarinpalMonetizationConstants.CUSTOMER_DESCRIPTION, "Customer for "
                    + subscriber.getName());
            customerParams.put(ZarinpalMonetizationConstants.CUSTOMER_SOURCE, ZarinpalMonetizationConstants.DEFAULT_TOKEN);
            //create a customer for subscriber in the platform account
            customer = Customer.create(customerParams);
            monetizationPlatformCustomer.setCustomerId(customer.getId());
            try {
                //returns the id of the inserted record
                int id = zarinpalMonetizationDAO.addBEPlatformCustomer(subscriber.getId(), subscriber.getTenantId(),
                        customer.getId());
                monetizationPlatformCustomer.setId(id);
            } catch (ZarinpalMonetizationException e) {
                if (customer != null) {
                    // deletes the customer if the customer is created in Zarinpal and failed to update in DB
                    customer.delete();
                }
                String errorMsg = "Error when inserting zarinpal customer details of " + subscriber.getName()
                        + " to Database";
                log.error(errorMsg);
                throw new WorkflowException(errorMsg, e);
            }
        } catch (ZarinpalException ex) {
            String errorMsg = "Error while creating a customer in Zarinpal for " + subscriber.getName();
            log.error(errorMsg);
            throw new WorkflowException(errorMsg, ex);
        }
        return monetizationPlatformCustomer;
    }

    /**
     * This method completes subscription creation workflow and return workflow response back to the caller
     *
     * @param workflowDTO The WorkflowDTO which contains workflow contextual information related to the workflow
     * @return workflow response back to the caller
     * @throws WorkflowException
     */
    @Override
    public WorkflowResponse complete(WorkflowDTO workflowDTO) throws WorkflowException {

        workflowDTO.setUpdatedTime(System.currentTimeMillis());
        super.complete(workflowDTO);
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        try {
            apiMgtDAO.updateSubscriptionStatus(Integer.parseInt(workflowDTO.getWorkflowReference()),
                    APIConstants.SubscriptionStatus.UNBLOCKED);
        } catch (APIManagementException e) {
            log.error("Could not complete subscription creation workflow", e);
            throw new WorkflowException("Could not complete subscription creation workflow", e);
        }
        return new GeneralWorkflowResponse();
    }

}
