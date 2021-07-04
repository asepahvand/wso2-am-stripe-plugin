/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.apim.monetization.impl;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.wso2.apim.monetization.impl.model.MonetizedSubscription;
import org.wso2.carbon.apimgt.api.APIAdmin;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.MonetizationException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.APIProduct;
import org.wso2.carbon.apimgt.api.model.APIProductIdentifier;
import org.wso2.carbon.apimgt.api.model.Monetization;
import org.wso2.carbon.apimgt.api.model.MonetizationUsagePublishInfo;
import org.wso2.carbon.apimgt.api.model.SubscribedAPI;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.carbon.apimgt.api.model.policy.SubscriptionPolicy;
import org.wso2.carbon.apimgt.impl.APIAdminImpl;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.user.api.UserStoreException;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * This class is used to implement zarinpal based monetization
 */
public class ZarinpalMonetizationImpl implements Monetization {

    private static final Log log = LogFactory.getLog(ZarinpalMonetizationImpl.class);
    private ZarinpalMonetizationDAO zarinpalMonetizationDAO = ZarinpalMonetizationDAO.getInstance();

    /**
     * Create billing plan for a policy
     *
     * @param subscriptionPolicy subscription policy
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean createBillingPlan(SubscriptionPolicy subscriptionPolicy) throws MonetizationException {

        try {
            Product product = new Product();
            product.setName(subscriptionPolicy.getTenantDomain() + "-" + subscriptionPolicy.getPolicyName());
            product.setType(ZarinpalMonetizationConstants.SERVICE_TYPE);
            try {
                zarinpalMonetizationDAO.addMonetizationProduct(product);
            } catch (ProductMonetizationException e) {
                String errorMessage = "Failed to create product for tenant : " +
                        subscriptionPolicy.getTenantDomain();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage);
            }
            Plan plan = new Plan();
            plan.setCurrency(subscriptionPolicy.getMonetizationPlanProperties().
                    get(APIConstants.Monetization.CURRENCY).toLowerCase());
            plan.setProductId(product.getId());
            plan.setProductNickname(subscriptionPolicy.getPolicyName());
            plan.setInterval(subscriptionPolicy.getMonetizationPlanProperties().get(APIConstants.Monetization.BILLING_CYCLE));
            if (APIConstants.Monetization.FIXED_RATE.equalsIgnoreCase(subscriptionPolicy.getMonetizationPlan())) {
                float amount = Float.parseFloat(subscriptionPolicy.getMonetizationPlanProperties().
                        get(APIConstants.Monetization.FIXED_PRICE));
                plan.setAmount((int) amount);
                plan.setUsageType(ZarinpalMonetizationConstants.LICENSED_USAGE);
            }
            if (ZarinpalMonetizationConstants.DYNAMIC_RATE.equalsIgnoreCase(subscriptionPolicy.getMonetizationPlan())) {
                float amount = Float.parseFloat(subscriptionPolicy.getMonetizationPlanProperties().
                        get(APIConstants.Monetization.PRICE_PER_REQUEST));
                plan.setAmount((int) amount);
                plan.setUsageType(ZarinpalMonetizationConstants.METERED_USAGE);
            }
            try {
                zarinpalMonetizationDAO.addMonetizationPlan(plan);
            } catch (ProductMonetizationException e) {
                String errorMessage = "Failed to create plan for tier : " + subscriptionPolicy.getPolicyName() +
                        " in " + subscriptionPolicy.getTenantDomain();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage);
            }
            //add database record
            zarinpalMonetizationDAO.addMonetizationPlanData(subscriptionPolicy, product.getId(), plan.getId());
            return true;
        } catch (ZarinpalMonetizationException e) {
            String errorMessage = "Failed to create monetization plan for : " + subscriptionPolicy.getPolicyName() +
                    " in the database.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
    }

    /**
     * Update billing plan of a policy
     *
     * @param subscriptionPolicy subscription policy
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean updateBillingPlan(SubscriptionPolicy subscriptionPolicy) throws MonetizationException {

        Map<String, String> planData = null;
        try {
            planData = zarinpalMonetizationDAO.getPlanData(subscriptionPolicy);
        } catch (ZarinpalMonetizationException e) {
            String errorMessage = "Failed to get stripe plan data for policy : " + subscriptionPolicy.getPolicyName() +
                    " when updating billing plan.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        String oldProductId = null;
        String oldPlanId = null;
        String newProductId = null;
        String updatedPlanId = null;
        if (MapUtils.isNotEmpty(planData)) {
            //product and plan exists for the older plan, so get those values and proceed
            oldProductId = planData.get(ZarinpalMonetizationConstants.PRODUCT_ID);
            oldPlanId = planData.get(ZarinpalMonetizationConstants.PLAN_ID);
        } else {
            //this means updating the monetization plan of tier from a free to commercial.
            //since there is no plan (for old - free tier), we should create a product and plan for the updated tier
            Product product = new Product();
            newProductId = product.getId();
            product.setName(subscriptionPolicy.getTenantDomain() + "-" + subscriptionPolicy.getPolicyName());
            product.setType(ZarinpalMonetizationConstants.SERVICE_TYPE);
            try {
                zarinpalMonetizationDAO.addMonetizationProduct(product);
            } catch (ProductMonetizationException e) {
                String errorMessage = "Failed to create product for tenant (when updating policy) : " +
                        subscriptionPolicy.getTenantDomain();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage);
            }
        }
        //delete old plan if exists
        if (StringUtils.isNotBlank(oldPlanId)) {
            try {
                zarinpalMonetizationDAO.deleteMonetizationPlan(oldPlanId);
            } catch (ProductMonetizationException e) {
                String errorMessage = "Failed to delete old plan for policy : " + subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                log.warn(errorMessage + " due to " + e);
            }
        }
        //if updated to a commercial plan, create new plan in billing engine and update DB record
        if (APIConstants.COMMERCIAL_TIER_PLAN.equalsIgnoreCase(subscriptionPolicy.getBillingPlan())) {
            Plan updatedPlan = new Plan();

            updatedPlan.setCurrency(subscriptionPolicy.getMonetizationPlanProperties().
                    get(APIConstants.Monetization.CURRENCY).toLowerCase());

            if (StringUtils.isNotBlank(oldProductId)) {
                updatedPlan.setProductId(oldProductId);
            }
            if (StringUtils.isNotBlank(newProductId)) {
                updatedPlan.setProductId(newProductId);
            }
            updatedPlan.setProductNickname(subscriptionPolicy.getPolicyName());
            updatedPlan.setInterval(subscriptionPolicy.getMonetizationPlanProperties().
                    get(APIConstants.Monetization.BILLING_CYCLE));

            if (APIConstants.Monetization.FIXED_RATE.equalsIgnoreCase(subscriptionPolicy.getMonetizationPlan())) {
                float amount = Float.parseFloat(subscriptionPolicy.getMonetizationPlanProperties().
                        get(APIConstants.Monetization.FIXED_PRICE));
                updatedPlan.setAmount((int) amount);
                updatedPlan.setUsageType(ZarinpalMonetizationConstants.LICENSED_USAGE);
            }
            if (ZarinpalMonetizationConstants.DYNAMIC_RATE.equalsIgnoreCase(subscriptionPolicy.getMonetizationPlan())) {
                float amount = Float.parseFloat(subscriptionPolicy.getMonetizationPlanProperties().
                        get(APIConstants.Monetization.PRICE_PER_REQUEST));
                updatedPlan.setAmount((int) amount);
                updatedPlan.setUsageType(ZarinpalMonetizationConstants.METERED_USAGE);
            }
            try {
                zarinpalMonetizationDAO.addMonetizationPlan(updatedPlan);
            } catch (ProductMonetizationException e) {
                String errorMessage = "Failed to create plan for tier : " + subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage, e);
            }
            updatedPlanId = updatedPlan.getId();
        } else if (APIConstants.BILLING_PLAN_FREE.equalsIgnoreCase(subscriptionPolicy.getBillingPlan())) {
            try {
                //If updated to a free plan (from a commercial plan), no need to create any plan in the billing engine
                //hence delete the DB record
                zarinpalMonetizationDAO.deleteMonetizationPlanData(subscriptionPolicy);
                //Remove old artifacts in the billing engine (if any)
                if (StringUtils.isNotBlank(oldProductId)) {
                    zarinpalMonetizationDAO.deleteMonetizationProduct(oldProductId);
                }
            } catch (ProductMonetizationException e) {
                String errorMessage = "Failed to delete old product for : " + subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage, e);
            } catch (ZarinpalMonetizationException e) {
                String errorMessage = "Failed to delete monetization plan data from database for : " +
                        subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage, e);
            }
        }
        try {
            if (StringUtils.isNotBlank(oldProductId)) {
                //update DB record
                zarinpalMonetizationDAO.updateMonetizationPlanData(subscriptionPolicy, oldProductId, updatedPlanId);
            }
            if (StringUtils.isNotBlank(newProductId)) {
                //create new DB record
                zarinpalMonetizationDAO.addMonetizationPlanData(subscriptionPolicy, newProductId, updatedPlanId);
            }
        } catch (ZarinpalMonetizationException e) {
            String errorMessage = "Failed to update monetization plan data in database for : " +
                    subscriptionPolicy.getPolicyName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        return true;
    }

    /**
     * Delete a billing plan of a policy
     *
     * @param subscriptionPolicy subscription policy
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean deleteBillingPlan(SubscriptionPolicy subscriptionPolicy) throws MonetizationException {

        //get old plan (if any) in the billing engine and delete
        Map<String, String> planData = null;
        try {
            planData = zarinpalMonetizationDAO.getPlanData(subscriptionPolicy);
        } catch (ZarinpalMonetizationException e) {
            String errorMessage = "Failed to get plan data for policy : " + subscriptionPolicy.getPolicyName() +
                    " when deleting billing plan.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        if (MapUtils.isEmpty(planData)) {
            log.debug("No billing plan found for : " + subscriptionPolicy.getPolicyName());
            return true;
        }
        String productId = planData.get(ZarinpalMonetizationConstants.PRODUCT_ID);
        String planId = planData.get(ZarinpalMonetizationConstants.PLAN_ID);

        if (StringUtils.isNotBlank(planId)) {
            try {
                zarinpalMonetizationDAO.deleteMonetizationPlan(planId);
                zarinpalMonetizationDAO.deleteMonetizationProduct(productId);
                zarinpalMonetizationDAO.deleteMonetizationPlanData(subscriptionPolicy);
            } catch (ProductMonetizationException e) {
                String errorMessage = "Failed to delete billing plan resources of : " + subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage, e);
            } catch (ZarinpalMonetizationException e) {
                String errorMessage = "Failed to delete billing plan data from database of policy : " +
                        subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage, e);
            }
        }
        return true;
    }

    /**
     * Enable monetization for a API
     *
     * @param tenantDomain           tenant domain
     * @param api                    API
     * @param monetizationProperties monetization properties map
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean enableMonetization(String tenantDomain, API api, Map<String, String> monetizationProperties)
            throws MonetizationException {

        String apiName = api.getId().getApiName();
        String apiVersion = api.getId().getVersion();
        String apiProvider = api.getId().getProviderName();
        try {
            int apiId = ApiMgtDAO.getInstance().getAPIID(api.getId(), APIMgtDBUtil.getConnection());
            String billingProductIdForApi = getBillingProductIdForApi(apiId);
            //create billing engine product if it does not exist
            if (StringUtils.isEmpty(billingProductIdForApi)) {
                Product product = new Product();
                product.setName(apiName + "-" + apiVersion + "-" + apiProvider);
                product.setType(ZarinpalMonetizationConstants.SERVICE_TYPE);
                try {
                    zarinpalMonetizationDAO.addMonetizationProduct(product);
                    billingProductIdForApi = product.getId();
                } catch (ProductMonetizationException e) {
                    String errorMessage = "Unable to create product for : " + apiName;
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(errorMessage);
                }
            }
            Map<String, String> tierPlanMap = new HashMap<String, String>();
            //scan for commercial tiers and add add plans in the billing engine if needed
            for (Tier currentTier : api.getAvailableTiers()) {
                if (APIConstants.COMMERCIAL_TIER_PLAN.equalsIgnoreCase(currentTier.getTierPlan())) {
                    String billingPlanId = getBillingPlanIdOfTier(apiId, currentTier.getName());
                    if (StringUtils.isBlank(billingPlanId)) {
                        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
                        String createdPlanId = createBillingPlanForCommercialTier(currentTier, tenantId,
                                billingProductIdForApi);
                        if (StringUtils.isNotBlank(createdPlanId)) {
                            log.debug("Billing plan : " + createdPlanId + " successfully created for : " +
                                    currentTier.getName());
                            tierPlanMap.put(currentTier.getName(), createdPlanId);
                        } else {
                            log.debug("Failed to create billing plan for : " + currentTier.getName());
                        }
                    }
                }
            }
            //save data in the database - only if there is a zarinpal product and newly created plans
            if (StringUtils.isNotBlank(billingProductIdForApi) && MapUtils.isNotEmpty(tierPlanMap)) {
                zarinpalMonetizationDAO.addMonetizationData(apiId, billingProductIdForApi, tierPlanMap);
            } else {
                return false;
            }
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get ID from database for : " + apiName;
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (ZarinpalMonetizationException e) {
            String errorMessage = "Failed to create products and plans for : " + apiName;
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (SQLException e) {
            String errorMessage = "Error while retrieving the API ID";
            throw new MonetizationException(errorMessage, e);
        }
        return true;
    }

    /**
     * Disable monetization for a API
     *
     * @param tenantDomain           tenant domain
     * @param api                    API
     * @param monetizationProperties monetization properties map
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean disableMonetization(String tenantDomain, API api, Map<String, String> monetizationProperties) throws MonetizationException {

        try {
            String apiName = api.getId().getApiName();
            int apiId = ApiMgtDAO.getInstance().getAPIID(api.getId(), APIMgtDBUtil.getConnection());
            String billingProductIdForApi = getBillingProductIdForApi(apiId);
            //no product in the billing engine, so return
            if (StringUtils.isBlank(billingProductIdForApi)) {
                return false;
            }
            Map<String, String> tierToBillingEnginePlanMap = zarinpalMonetizationDAO.getTierToBillingEnginePlanMapping
                    (apiId, billingProductIdForApi);
            for (Map.Entry<String, String> entry : tierToBillingEnginePlanMap.entrySet()) {
                String planId = entry.getValue();
                zarinpalMonetizationDAO.deleteMonetizationPlan(planId);
                log.debug("Successfully deleted billing plan : " + planId + " of tier : " + entry.getKey());
            }
            //after deleting all the associated plans, then delete the product
            zarinpalMonetizationDAO.deleteMonetizationProduct(billingProductIdForApi);
            log.debug("Successfully deleted billing product : " + billingProductIdForApi + " of : " + apiName);
            //after deleting plans and the product, clean the database records
            zarinpalMonetizationDAO.deleteMonetizationData(apiId);
            log.debug("Successfully deleted monetization database records for : " + apiName);
        } catch (ProductMonetizationException e) {
            String errorMessage = "Failed to delete products and plans in the billing engine.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (ZarinpalMonetizationException e) {
            String errorMessage = "Failed to fetch database records when disabling monetization for : " +
                    api.getId().getApiName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get ID from database for : " + api.getId().getApiName() +
                    " when disabling monetization.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (SQLException e) {
            String errorMessage = "Error while retrieving the API ID";
            throw new MonetizationException(errorMessage, e);
        }
        return true;
    }

    /**
     * Get mapping of tiers and billing engine plans
     *
     * @param api API
     * @return tier to billing plan mapping
     * @throws MonetizationException if failed to get tier to billing plan mapping
     */
    public Map<String, String> getMonetizedPoliciesToPlanMapping(API api) throws MonetizationException {

        try {
            String apiName = api.getId().getApiName();
            Connection con = APIMgtDBUtil.getConnection();
            int apiId = ApiMgtDAO.getInstance().getAPIID(api.getId(), con);
            //get billing engine product ID for that API
            String billingProductIdForApi = getBillingProductIdForApi(apiId);
            if (StringUtils.isEmpty(billingProductIdForApi)) {
                log.info("No product was found in billing engine for  : " + apiName);
                return new HashMap<String, String>();
            }
            //get tier to billing engine plan mapping
            return zarinpalMonetizationDAO.getTierToBillingEnginePlanMapping(apiId, billingProductIdForApi);
        } catch (ZarinpalMonetizationException e) {
            String errorMessage = "Failed to get tier to billing engine plan mapping for : " + api.getId().getApiName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get ID from database for : " + api.getId().getApiName() +
                    " when getting tier to billing engine plan mapping.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (SQLException e) {
            String errorMessage = "Error while retrieving the API ID";
            throw new MonetizationException(errorMessage, e);
        }
    }

    /**
     * Publish monetization usage count
     *
     * @param lastPublishInfo Info about last published task
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean publishMonetizationUsageRecords(MonetizationUsagePublishInfo lastPublishInfo)
            throws MonetizationException {

//        String apiName = null;
//        String apiVersion = null;
//        String tenantDomain = null;
//        String applicationName = null;
//        String applicationOwner = null;
//        int applicationId;
//        String apiProvider = null;
//        Long requestCount = 0L;
//        Long currentTimestamp;
//        int flag = 0;
//        int counter = 0;
//        APIAdmin apiAdmin = new APIAdminImpl();
//
//        Date dateobj = new Date();
//        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(ZarinpalMonetizationConstants.TIME_FORMAT);
//        simpleDateFormat.setTimeZone(TimeZone.getTimeZone(ZarinpalMonetizationConstants.TIME_ZONE));
//        String toDate = simpleDateFormat.format(dateobj);
//        //used for zarinpal recording
//        currentTimestamp = getTimestamp(toDate);
//        //The implementation will be improved to use offset date time to get the time zone based on user input
//        String formattedToDate = toDate.concat(ZarinpalMonetizationConstants.TIMEZONE_FORMAT);
//        String fromDate = simpleDateFormat.format(
//                new java.util.Date(lastPublishInfo.getLastPublishTime()));
//        //The implementation will be improved to use offset date time to get the time zone based on user input
//        String formattedFromDate = fromDate.concat(ZarinpalMonetizationConstants.TIMEZONE_FORMAT);
//        LinkedTreeMap<String, ArrayList<LinkedTreeMap<String, String>>> data = getUsageData(formattedFromDate,
//                formattedToDate);
//        if (data.get(ZarinpalMonetizationConstants.GET_USAGE_BY_APPLICATION).isEmpty()) {
//            try {
//                log.debug("No API Usage retrieved for the given period of time");
//                //last publish time will be updated as successful since there was no usage retrieved.
//                lastPublishInfo.setLastPublishTime(currentTimestamp);
//                lastPublishInfo.setState(ZarinpalMonetizationConstants.COMPLETED);
//                lastPublishInfo.setStatus(ZarinpalMonetizationConstants.SUCCESSFULL);
//                apiAdmin.updateMonetizationUsagePublishInfo(lastPublishInfo);
//            } catch (APIManagementException ex) {
//                String msg = "Failed to update last published time ";
//                //throw MonetizationException as it will be logged and handled by the caller
//                throw new MonetizationException(msg, ex);
//            }
//            return true;
//        }
//        for (Map.Entry<String, ArrayList<LinkedTreeMap<String, String>>> entry : data.entrySet()) {
//            String key = entry.getKey();
//            ArrayList<LinkedTreeMap<String, String>> apiUsageDataCollection = entry.getValue();
//            for (LinkedTreeMap<String, String> apiUsageData : apiUsageDataCollection) {
//                apiName = apiUsageData.get(ZarinpalMonetizationConstants.API_NAME);
//                apiVersion = apiUsageData.get(ZarinpalMonetizationConstants.API_VERSION);
//                tenantDomain = apiUsageData.get(ZarinpalMonetizationConstants.TENANT_DOMAIN);
//                applicationName = apiUsageData.get(ZarinpalMonetizationConstants.APPLICATION_NAME);
//                applicationOwner = apiUsageData.get(ZarinpalMonetizationConstants.APPLICATION_OWNER);
//                try {
//                    applicationId = apiMgtDAO.getApplicationId(applicationName, applicationOwner);
//                    apiProvider = apiMgtDAO.getAPIProviderByNameAndVersion(apiName, apiVersion, tenantDomain);
//                } catch (APIManagementException e) {
//                    throw new MonetizationException("Error while retrieving Application Id for application " +
//                            applicationName, e);
//                }
//                requestCount = Long.parseLong(apiUsageData.get(ZarinpalMonetizationConstants.COUNT));
//                try {
//                    //get the billing engine subscription details
//                    MonetizedSubscription subscription = zarinpalMonetizationDAO.getMonetizedSubscription(apiName,
//                            apiVersion, apiProvider, applicationId, tenantDomain);
////                    if (subscription.getSubscriptionId() != null) {
////                        try {
////                            //start the tenant flow to get the platform key
////                            PrivilegedCarbonContext.startTenantFlow();
////                            PrivilegedCarbonContext.getThreadLocalCarbonContext().
////                                    setTenantDomain(tenantDomain, true);
////                            //read tenant conf and get platform account key
////                            Stripe.apiKey = getZarinpalMerchantID(tenantDomain);
////                        } catch (ZarinpalMonetizationException e) {
////                            String errorMessage = "Failed to get Zarinpal platform account key for tenant :  " +
////                                    tenantDomain + " when disabling monetization for : " + apiName;
////                            //throw MonetizationException as it will be logged and handled by the caller
////                            throw new MonetizationException(errorMessage, e);
////                        } finally {
////                            PrivilegedCarbonContext.endTenantFlow();
////                        }
////                    }
////                    String connectedAccountKey;
////                    try {
////                        PrivilegedCarbonContext.startTenantFlow();
////                        PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(
////                                tenantDomain, true);
////                        apiProvider = APIUtil.replaceEmailDomain(apiProvider);
////                        APIIdentifier identifier = new APIIdentifier(apiProvider, apiName, apiVersion);
////                        APIProvider apiProvider1 = APIManagerFactory.getInstance().getAPIProvider(apiProvider);
////                        API api = apiProvider1.getAPI(identifier);
////                        Map<String, String> monetizationProperties = new Gson().fromJson(
////                                api.getMonetizationProperties().toString(), HashMap.class);
////                        //get api publisher's zarinpal key (i.e - connected account key) from monetization
////                        // properties in request payload
////                        if (MapUtils.isNotEmpty(monetizationProperties) &&
////                                monetizationProperties.containsKey(
////                                        ZarinpalMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
////                            connectedAccountKey = monetizationProperties.get
////                                    (ZarinpalMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
////                            if (StringUtils.isBlank(connectedAccountKey)) {
////                                String errorMessage = "Connected account zarinpal key was not found for : "
////                                        + api.getId().getApiName();
////                                //throw MonetizationException as it will be logged and handled by the caller
////                                throw new MonetizationException(errorMessage);
////                            }
////                        } else {
////                            String errorMessage = "Zarinpal key of the connected account is empty.";
////                            //throw MonetizationException as it will be logged and handled by the caller
////                            throw new MonetizationException(errorMessage);
////                        }
////                    } catch (APIManagementException e) {
////                        String errorMessage = "Failed to get the Zarinpal key of the connected account from "
////                                + "the : " + apiName;
////                        //throw MonetizationException as it will be logged and handled by the caller
////                        throw new MonetizationException(errorMessage, e);
////                    } finally {
////                        PrivilegedCarbonContext.endTenantFlow();
////                    }
//                    Plan plan = zarinpalMonetizationDAO.getMonetizationPlan(subscription.getPlanId());
//                    RequestOptions subRequestOptions = RequestOptions.builder().
//                            setStripeAccount(connectedAccountKey).build();
//                     sub = Subscription.retrieve(subscription.getSubscriptionId(),
//                            subRequestOptions);
//                    //get the first subscription item from the array
//                    subscriptionItem = sub.getItems().getData().get(0);
//                    //check whether the billing plan is Usage Based.
//                    if (plan.getUsageType().equals(
//                            ZarinpalMonetizationConstants.METERED_PLAN)) {
//                        flag++;
//                        Map<String, Object> usageRecordParams = new HashMap<>();
//                        usageRecordParams.put(ZarinpalMonetizationConstants.QUANTITY, requestCount);
//                        //provide the timesatmp in second format
//                        usageRecordParams.put(ZarinpalMonetizationConstants.TIMESTAMP,
//                                currentTimestamp / 1000);
//                        usageRecordParams.put(ZarinpalMonetizationConstants.ACTION,
//                                ZarinpalMonetizationConstants.INCREMENT);
//                        RequestOptions usageRequestOptions = RequestOptions.builder().
//                                setStripeAccount(connectedAccountKey).setIdempotencyKey(sub.getId()
//                                + lastPublishInfo.getLastPublishTime() + requestCount).build();
//                        UsageRecord usageRecord = UsageRecord.createOnSubscriptionItem(
//                                sub.getId(), usageRecordParams, usageRequestOptions);
//                        //checks whether the usage record is published successfully
//                        if (usageRecord.getId() != null) {
//                            counter++;
//                            if (log.isDebugEnabled()) {
//                                String msg = "Usage for " + apiName + " by Application with ID " + applicationId
//                                        + " is successfully published to Zarinpal";
//                                log.debug(msg);
//                            }
//                        }
//                    }
//                } catch (ZarinpalMonetizationException e) {
//                    String errorMessage = "Unable to Publish usage Record to Billing Engine";
//                    //throw MonetizationException as it will be logged and handled by the caller
//                    throw new MonetizationException(errorMessage, e);
//                } catch (ProductMonetizationException e) {
//                    String errorMessage = "Unable to Publish usage Record";
//                    //throw MonetizationException as it will be logged and handled by the caller
//                    throw new MonetizationException(errorMessage, e);
//                }
//            }
//        }
//
//        //Flag equals counter when all the records are published successfully
//        if (flag == counter) {
//            try {
//                //last publish time will be updated only if all the records are successful
//                lastPublishInfo.setLastPublishTime(currentTimestamp);
//                lastPublishInfo.setState(ZarinpalMonetizationConstants.COMPLETED);
//                lastPublishInfo.setStatus(ZarinpalMonetizationConstants.SUCCESSFULL);
//                apiAdmin.updateMonetizationUsagePublishInfo(lastPublishInfo);
//            } catch (APIManagementException ex) {
//                String msg = "Failed to update last published time ";
//                //throw MonetizationException as it will be logged and handled by the caller
//                throw new MonetizationException(msg, ex);
//            }
//            return true;
//        }
//        try {
//            lastPublishInfo.setState(ZarinpalMonetizationConstants.COMPLETED);
//            lastPublishInfo.setStatus(ZarinpalMonetizationConstants.UNSUCCESSFULL);
//            apiAdmin.updateMonetizationUsagePublishInfo(lastPublishInfo);
//        } catch (APIManagementException ex) {
//            String msg = "Failed to update last published time ";
//            //throw MonetizationException as it will be logged and handled by the caller
//            throw new MonetizationException(msg, ex);
//        }
//        return false;
        return true;
    }

    /**
     * Get usage data for all monetized APIs from Choreo Analytics between the given time.
     *
     * @param formattedFromDate The starting date of the time range
     * @param formattedToDate   The ending date of the time range
     * @return usage data of monetized APIs
     * @throws MonetizationException if failed to get the usage for the APIs
     */
    LinkedTreeMap<String, ArrayList<LinkedTreeMap<String, String>>> getUsageData(
            String formattedFromDate, String formattedToDate) throws MonetizationException {

//        String accessToken = null;
//        String queryApiEndpoint = null;
//        String graphQLquery = "query($timeFilter: TimeFilter!, " +
//                "$successAPIUsageByAppFilter: SuccessAPIUsageByAppFilter!) " +
//                "{getSuccessAPIsUsageByApplications(timeFilter: $timeFilter, " +
//                "successAPIUsageByAppFilter: $successAPIUsageByAppFilter) { apiId apiName apiVersion " +
//                "apiCreatorTenantDomain applicationId applicationName applicationOwner count}}";
//
//        if (config == null) {
//            // Retrieve the access token from api manager configurations.
//            config = ServiceReferenceHolder.getInstance().getAPIManagerConfigurationService().
//                    getAPIManagerConfiguration();
//        }
//        queryApiEndpoint = config.getFirstProperty(
//                ZarinpalMonetizationConstants.ANALYTICS_QUERY_API_ENDPOINT_PROP);
//        if (StringUtils.isEmpty(queryApiEndpoint)) {
//            throw new MonetizationException("Endpoint for the the analytics query api is not configured");
//        }
//        accessToken = config.getFirstProperty(ZarinpalMonetizationConstants.ANALYTICS_ACCESS_TOKEN_PROP);
//        if (StringUtils.isEmpty(accessToken)) {
//            throw new MonetizationException("Access token for the the analytics query api is not configured");
//        }
//
//        JSONObject timeFilter = new JSONObject();
//        timeFilter.put(ZarinpalMonetizationConstants.FROM, formattedFromDate);
//        timeFilter.put(ZarinpalMonetizationConstants.TO, formattedToDate);
//        JSONArray monetizedAPIIds = new JSONArray();
//        JSONArray tenantDomains = new JSONArray();
//
//        try {
//            Properties properties = new Properties();
//            properties.put(APIConstants.ALLOW_MULTIPLE_STATUS, APIUtil.isAllowDisplayAPIsWithMultipleStatus());
//            apiPersistenceInstance = PersistenceManager.getPersistenceInstance(properties);
//            List<Tenant> tenants = APIUtil.getAllTenantsWithSuperTenant();
//            for (Tenant tenant : tenants) {
//                tenantDomains.add(tenant.getDomain());
//                try {
//                    PrivilegedCarbonContext.startTenantFlow();
//                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(
//                            tenant.getDomain(), true);
//                    APIProvider apiProviderNew = RestApiCommonUtil.getProvider(APIUtil.getAdminUsername());
//                    List<API> allowedAPIs = apiProviderNew.getAllAPIs();
//                    Organization org = new Organization(tenant.getDomain());
//                    for (API api : allowedAPIs) {
//                        PublisherAPI publisherAPI = null;
//                        try {
//                            publisherAPI = apiPersistenceInstance.getPublisherAPI(org, api.getUUID());
//                            if (publisherAPI.isMonetizationEnabled()) {
//                                monetizedAPIIds.add(api.getUUID());
//                            }
//                        } catch (APIPersistenceException e) {
//                            throw new MonetizationException("Failed to retrieve the API of UUID: " + api.getUUID(), e);
//                        }
//                    }
//                } catch (APIManagementException e) {
//                    throw new MonetizationException("Error while retrieving the Ids of Monetized APIs");
//                }
//            }
//        } catch (UserStoreException e) {
//            throw new MonetizationException("Error while retrieving the tenants", e);
//        }
//        if (monetizedAPIIds.size() > 0) {
//            JSONObject successAPIUsageByAppFilter = new JSONObject();
//            successAPIUsageByAppFilter.put(ZarinpalMonetizationConstants.API_ID_COL, monetizedAPIIds);
//            successAPIUsageByAppFilter.put(ZarinpalMonetizationConstants.TENANT_DOMAIN_COL, tenantDomains);
//            JSONObject variables = new JSONObject();
//            variables.put(ZarinpalMonetizationConstants.TIME_FILTER, timeFilter);
//            variables.put(ZarinpalMonetizationConstants.API_USAGE_BY_APP_FILTER, successAPIUsageByAppFilter);
//            GraphQLClient graphQLCliet =
//                    Feign.builder().client(new OkHttpClient()).encoder(new GsonEncoder()).decoder(new GsonDecoder())
//                            .logger(new Slf4jLogger()).requestInterceptor(new QueyAPIAccessTokenInterceptor(accessToken))
//                            .target(GraphQLClient.class, queryApiEndpoint);
//            GraphqlQueryModel queryModel = new GraphqlQueryModel();
//            queryModel.setQuery(graphQLquery);
//            queryModel.setVariables(variables);
//            graphQLResponseClient usageResponse = graphQLCliet.getSuccessAPIsUsageByApplications(queryModel);
//            return usageResponse.getData();
//        }
        return null;
    }

    /**
     * Get current usage for a subscription
     *
     * @param subscriptionUUID subscription UUID
     * @param apiProvider      API provider
     * @return current usage for a subscription
     * @throws MonetizationException if failed to get current usage for a subscription
     */
    public Map<String, String> getCurrentUsageForSubscription(String subscriptionUUID, APIProvider apiProvider)
            throws MonetizationException {

//        Map<String, String> billingEngineUsageData = new HashMap<>();
//        String apiName = null;
//        try {
//            SubscribedAPI subscribedAPI = ApiMgtDAO.getInstance().getSubscriptionByUUID(subscriptionUUID);
//            APIIdentifier apiIdentifier = subscribedAPI.getApiId();
//            APIProductIdentifier apiProductIdentifier;
//            API api;
//            APIProduct apiProduct;
//            HashMap monetizationDataMap;
//            int apiId;
//            if (apiIdentifier != null) {
//                api = apiProvider.getAPI(apiIdentifier);
//                apiName = apiIdentifier.getApiName();
//                if (api.getMonetizationProperties() == null) {
//                    String errorMessage = "Monetization properties are empty for : " + apiName;
//                    //throw MonetizationException as it will be logged and handled by the caller
//                    throw new MonetizationException(errorMessage);
//                }
//                monetizationDataMap = new Gson().fromJson(api.getMonetizationProperties().toString(), HashMap.class);
//                if (MapUtils.isEmpty(monetizationDataMap)) {
//                    String errorMessage = "Monetization data map is empty for : " + apiName;
//                    //throw MonetizationException as it will be logged and handled by the caller
//                    throw new MonetizationException(errorMessage);
//                }
//                apiId = ApiMgtDAO.getInstance().getAPIID(apiIdentifier, APIMgtDBUtil.getConnection());
//            } else {
//                apiProductIdentifier = subscribedAPI.getProductId();
//                apiProduct = apiProvider.getAPIProduct(apiProductIdentifier);
//                apiName = apiProductIdentifier.getName();
//                if (apiProduct.getMonetizationProperties() == null) {
//                    String errorMessage = "Monetization properties are empty for : " + apiName;
//                    //throw MonetizationException as it will be logged and handled by the caller
//                    throw new MonetizationException(errorMessage);
//                }
//                monetizationDataMap = new Gson().fromJson(apiProduct.getMonetizationProperties().toString(), HashMap.class);
//                if (MapUtils.isEmpty(monetizationDataMap)) {
//                    String errorMessage = "Monetization data map is empty for : " + apiName;
//                    //throw MonetizationException as it will be logged and handled by the caller
//                    throw new MonetizationException(errorMessage);
//                }
//                apiId = ApiMgtDAO.getInstance().getAPIProductId(apiProductIdentifier);
//            }
//            String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
//            //get billing engine platform account key
//            String platformAccountKey = getZarinpalMerchantID(tenantDomain);
//            if (monetizationDataMap.containsKey(ZarinpalMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
//                String connectedAccountKey = monetizationDataMap.get
//                        (ZarinpalMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY).toString();
//                if (StringUtils.isBlank(connectedAccountKey)) {
//                    String errorMessage = "Connected account zarinpal key was not found for : " + apiName;
//                    //throw MonetizationException as it will be logged and handled by the caller
//                    throw new MonetizationException(errorMessage);
//                }
//                Stripe.apiKey = platformAccountKey;
//                //create request options to link with the connected account
//                RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
//                int applicationId = subscribedAPI.getApplication().getId();
//                MonetizedSubscription monetizedSubscription = zarinpalMonetizationDAO.getBillingEngineSubscriptionId(apiId, applicationId);
//                Plan plan = zarinpalMonetizationDAO.getMonetizationPlan(monetizedSubscription.getPlanId());
//                Subscription billingEngineSubscription = Subscription.retrieve(monetizedSubscription, requestOptions);
//                if (billingEngineSubscription == null) {
//                    String errorMessage = "No billing engine subscription was found for : " + apiName;
//                    //throw MonetizationException as it will be logged and handled by the caller
//                    throw new MonetizationException(errorMessage);
//                }
//                //upcoming invoice is only applicable for metered usage (i.e - dynamic usage)
//                if (!ZarinpalMonetizationConstants.METERED_USAGE.equalsIgnoreCase
//                        (plan.getUsageType())) {
//                    String errorMessage = "Usage type should be set to 'metered' to get the pending bill.";
//                    //throw MonetizationException as it will be logged and handled by the caller
//                    throw new MonetizationException(errorMessage);
//                }
//                Map<String, Object> invoiceParams = new HashMap<>();
//                invoiceParams.put("subscription", billingEngineSubscription.getId());
//                //fetch the upcoming invoice
//                Invoice invoice = Invoice.upcoming(invoiceParams, requestOptions);
//                if (invoice == null) {
//                    String errorMessage = "No billing engine subscription was found for : " + apiName;
//                    //throw MonetizationException as it will be logged and handled by the caller
//                    throw new MonetizationException(errorMessage);
//                }
//                SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
//                dateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
//                //the below parameters are billing engine specific
//                billingEngineUsageData.put("Description", invoice.getDescription());
//                billingEngineUsageData.put("Paid", invoice.getPaid() != null ? invoice.getPaid().toString() : null);
//                billingEngineUsageData.put("Tax", invoice.getTax() != null ?
//                        invoice.getTax().toString() : null);
//                billingEngineUsageData.put("Invoice ID", invoice.getId());
//                billingEngineUsageData.put("Account Name", invoice.getAccountName());
//                billingEngineUsageData.put("Next Payment Attempt", invoice.getNextPaymentAttempt() != null ?
//                        dateFormatter.format(new Date(invoice.getNextPaymentAttempt() * 1000)) : null);
//                billingEngineUsageData.put("Customer Email", invoice.getCustomerEmail());
//                billingEngineUsageData.put("Currency", invoice.getCurrency());
//                billingEngineUsageData.put("Account Country", invoice.getAccountCountry());
//                billingEngineUsageData.put("Amount Remaining", invoice.getAmountRemaining() != null ?
//                        Long.toString(invoice.getAmountRemaining() / 100L) : null);
//                billingEngineUsageData.put("Period End", invoice.getPeriodEnd() != null ?
//                        dateFormatter.format(new Date(invoice.getPeriodEnd() * 1000)) : null);
//                billingEngineUsageData.put("Due Date", invoice.getDueDate() != null ?
//                        dateFormatter.format(new Date(invoice.getDueDate())) : null);
//                billingEngineUsageData.put("Amount Due", invoice.getAmountDue() != null ?
//                        Long.toString(invoice.getAmountDue() / 100L) : null);
//                billingEngineUsageData.put("Total Tax Amounts", invoice.getTotalTaxAmounts() != null ?
//                        invoice.getTotalTaxAmounts().toString() : null);
//                billingEngineUsageData.put("Amount Paid", invoice.getAmountPaid() != null ?
//                        Long.toString(invoice.getAmountPaid() / 100L) : null);
//                billingEngineUsageData.put("Subtotal", invoice.getSubtotal() != null ?
//                        Long.toString(invoice.getSubtotal() / 100L) : null);
//                billingEngineUsageData.put("Total", invoice.getTotal() != null ?
//                        Long.toString(invoice.getTotal() / 100L) : null);
//                billingEngineUsageData.put("Period Start", invoice.getPeriodStart() != null ?
//                        dateFormatter.format(new Date(invoice.getPeriodStart() * 1000)) : null);
//
//                //the below parameters are also returned from stripe, but commented for simplicity of the invoice
//                /*billingEngineUsageData.put("object", "invoice");
//                billingEngineUsageData.put("Application Fee Amount", invoice.getApplicationFeeAmount() != null ?
//                        invoice.getApplicationFeeAmount().toString() : null);
//                billingEngineUsageData.put("Attempt Count", invoice.getAttemptCount() != null ?
//                        invoice.getAttemptCount().toString() : null);
//                billingEngineUsageData.put("Attempted", invoice.getAttempted() != null ?
//                        invoice.getAttempted().toString() : null);
//                billingEngineUsageData.put("Billing", invoice.getBilling());
//                billingEngineUsageData.put("Billing Reason", invoice.getBillingReason());
//                billingEngineUsageData.put("Charge", invoice.getCharge());
//                billingEngineUsageData.put("Created", invoice.getCreated() != null ? invoice.getCreated().toString() : null);
//                billingEngineUsageData.put("Customer", invoice.getCustomer());
//                billingEngineUsageData.put("Customer Address", invoice.getCustomerAddress() != null ?
//                        invoice.getCustomerAddress().toString() : null);
//                billingEngineUsageData.put("Customer Name", invoice.getCustomerName());
//                billingEngineUsageData.put("Ending Balance", invoice.getEndingBalance() != null ?
//                        invoice.getEndingBalance().toString() : null);
//                billingEngineUsageData.put("Livemode", invoice.getLivemode() != null ? invoice.getLivemode().toString() : null);
//                billingEngineUsageData.put("Number", invoice.getNumber());
//                billingEngineUsageData.put("Payment Intent", invoice.getPaymentIntent());
//                billingEngineUsageData.put("Post Payment Credit Notes Amount",
//                        invoice.getPostPaymentCreditNotesAmount() != null ? invoice.getPostPaymentCreditNotesAmount().toString() : null);
//                billingEngineUsageData.put("Pre Payment Credit Notes Amount",
//                        invoice.getPrePaymentCreditNotesAmount() != null ? invoice.getPrePaymentCreditNotesAmount().toString() : null);
//                billingEngineUsageData.put("Receipt Number", invoice.getReceiptNumber());
//                billingEngineUsageData.put("Subscription", invoice.getSubscription());
//                billingEngineUsageData.put("Tax Percent", invoice.getTaxPercent() != null ?
//                        invoice.getTaxPercent().toString() : null);*/
//            }
//        } catch (ProductMonetizationException e) {
//            String errorMessage = "Error while fetching billing engine usage data for : " + apiName;
//            //throw MonetizationException as it will be logged and handled by the caller
//            throw new MonetizationException(errorMessage, e);
//        } catch (APIManagementException e) {
//            String errorMessage = "Failed to get subscription details of : " + apiName;
//            //throw MonetizationException as it will be logged and handled by the caller
//            throw new MonetizationException(errorMessage, e);
//        } catch (ZarinpalMonetizationException e) {
//            String errorMessage = "Failed to get billing engine data for subscription : " + subscriptionUUID;
//            //throw MonetizationException as it will be logged and handled by the caller
//            throw new MonetizationException(errorMessage, e);
//        } catch (SQLException e) {
//            String errorMessage = "Error while retrieving the API ID";
//            throw new MonetizationException(errorMessage, e);
//        }

//        return billingEngineUsageData;

        String errorMessage = "Not implemented!";
        //throw MonetizationException as it will be logged and handled by the caller
        throw new MonetizationException(errorMessage);
    }

    /**
     * Get total revenue for a given API from all subscriptions
     *
     * @param api         API
     * @param apiProvider API provider
     * @return total revenue data for a given API from all subscriptions
     * @throws MonetizationException if failed to get total revenue data for a given API
     */
    public Map<String, String> getTotalRevenue(API api, APIProvider apiProvider) throws MonetizationException {

        APIIdentifier apiIdentifier = api.getId();
        Map<String, String> revenueData = new HashMap<String, String>();
        try {
            //get all subscriptions for the API
            List<SubscribedAPI> apiUsages = apiProvider.getAPIUsageByAPIId(apiIdentifier);
            for (SubscribedAPI subscribedAPI : apiUsages) {
                //get subscription UUID for each subscription
                int subscriptionId = subscribedAPI.getSubscriptionId();
                String subscriptionUUID = zarinpalMonetizationDAO.getSubscriptionUUID(subscriptionId);
                //get revenue for each subscription and add them
                Map<String, String> billingEngineUsageData = getCurrentUsageForSubscription(subscriptionUUID, apiProvider);
                revenueData.put("Revenue for subscription ID : " + subscriptionId,
                        billingEngineUsageData.get("amount_due"));
            }
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get subscriptions of : " + apiIdentifier.getApiName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (ZarinpalMonetizationException e) {
            String errorMessage = "Failed to get subscription UUID of : " + apiIdentifier.getApiName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        return revenueData;
    }

    /**
     * This method is used to get zarinpal platform account key for a given tenant
     *
     * @param tenantDomain tenant domain
     * @return zarinpal platform account key for the given tenant
     * @throws ZarinpalMonetizationException if it fails to get zarinpal platform account key for the given tenant
     */
    private String getZarinpalPlatformAccountKey(String tenantDomain) throws ZarinpalMonetizationException {

        try {
            int tenantId = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager().
                    getTenantId(tenantDomain);
            Registry configRegistry = ServiceReferenceHolder.getInstance().getRegistryService().
                    getConfigSystemRegistry(tenantId);
            if (configRegistry.resourceExists(APIConstants.API_TENANT_CONF_LOCATION)) {
                Resource resource = configRegistry.get(APIConstants.API_TENANT_CONF_LOCATION);
                String tenantConfContent = new String((byte[]) resource.getContent(), Charset.defaultCharset());
                if (StringUtils.isBlank(tenantConfContent)) {
                    String errorMessage = "Tenant configuration for tenant " + tenantDomain +
                            " cannot be empty when configuring monetization.";
                    throw new ZarinpalMonetizationException(errorMessage);
                }
                //get the zarinpal key of platform account from  tenant conf json file
                JSONObject tenantConfig = (JSONObject) new JSONParser().parse(tenantConfContent);
                JSONObject monetizationInfo = (JSONObject) tenantConfig.get(ZarinpalMonetizationConstants.MONETIZATION_INFO);
                String zarinpalMerchantID = monetizationInfo.get
                        (ZarinpalMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY).toString();
                if (StringUtils.isBlank(zarinpalMerchantID)) {
                    String errorMessage = "Zarinpal MerchantID is empty for tenant : " + tenantDomain;
                    throw new ZarinpalMonetizationException(errorMessage);
                }
                return zarinpalMerchantID;
            }
        } catch (ParseException e) {
            String errorMessage = "Error while parsing tenant configuration in tenant : " + tenantDomain;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } catch (UserStoreException e) {
            String errorMessage = "Failed to get the corresponding tenant configurations for tenant :  " + tenantDomain;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } catch (RegistryException e) {
            String errorMessage = "Failed to get the configuration registry for tenant :  " + tenantDomain;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        }
        return StringUtils.EMPTY;
    }

    /**
     * Get billing product ID for a given API
     *
     * @param apiId API ID
     * @return billing product ID for the given API
     * @throws ZarinpalMonetizationException if failed to get billing product ID for the given API
     */
    private String getBillingProductIdForApi(int apiId) throws ZarinpalMonetizationException {

        String billingProductId = StringUtils.EMPTY;
        billingProductId = zarinpalMonetizationDAO.getBillingEngineProductId(apiId);
        return billingProductId;
    }

    /**
     * Get billing plan ID for a given tier
     *
     * @param apiId    API ID
     * @param tierName tier name
     * @return billing plan ID for a given tier
     * @throws ZarinpalMonetizationException if failed to get billing plan ID for the given tier
     */
    private String getBillingPlanIdOfTier(int apiId, String tierName) throws ZarinpalMonetizationException {

        String billingPlanId = StringUtils.EMPTY;
        billingPlanId = zarinpalMonetizationDAO.getBillingEnginePlanIdForTier(apiId, tierName);
        return billingPlanId;
    }

    /**
     * Create billing plan for a given commercial tier
     *
     * @param tier             tier
     * @param tenantId         tenant ID
     * @param billingProductId billing engine product ID
     * @return created plan ID in billing engine
     * @throws ZarinpalMonetizationException if fails to create billing plan
     */
    private String createBillingPlanForCommercialTier(Tier tier, int tenantId, String billingProductId)
            throws ZarinpalMonetizationException {

        try {
            String tierUUID = ApiMgtDAO.getInstance().getSubscriptionPolicy(tier.getName(), tenantId).getUUID();
            //get plan ID from mapping table
            String planId = zarinpalMonetizationDAO.getBillingPlanId(tierUUID);
            //get that plan details
            Plan billingPlan = zarinpalMonetizationDAO.getMonetizationPlan(planId);
            //get the values from that plan and replicate it
            Plan createdPlan = new Plan();
            createdPlan.setAmount(billingPlan.getAmount());
            createdPlan.setInterval(billingPlan.getInterval());
            createdPlan.setProductNickname(billingPlan.getProductNickname());
            createdPlan.setProductId(billingProductId);
            createdPlan.setCurrency(billingPlan.getCurrency());
            createdPlan.setUsageType(billingPlan.getUsageType());
            zarinpalMonetizationDAO.addMonetizationPlan(createdPlan);
            return createdPlan.getId();
        } catch (ProductMonetizationException e) {
            String errorMessage = "Unable to create billing plan for : " + tier.getName();
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get UUID for tier :  " + tier.getName();
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        }
    }

    /**
     * The method converts the date into timestamp
     *
     * @param date
     * @return Timestamp in long format
     */
    private long getTimestamp(String date) {

        SimpleDateFormat formatter = new SimpleDateFormat(ZarinpalMonetizationConstants.TIME_FORMAT);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        long time = 0;
        Date parsedDate = null;
        try {
            parsedDate = formatter.parse(date);
            time = parsedDate.getTime();
        } catch (java.text.ParseException e) {
            log.error("Error while parsing the date ", e);
        }
        return time;
    }

//    public List<API> getAllAPIs(String tenantDomain, String username) throws APIManagementException {
//        Properties persistenceProperties = new Properties();
//        APIPersistence apiPersistenceInstance = PersistenceManager.getPersistenceInstance(persistenceProperties);
//        List<API> apiSortedList = new ArrayList<API>();
//        Organization org = new Organization(tenantDomain);
//        String[] roles = APIUtil.getFilteredUserRoles(username);
//        Map<String, Object> properties = APIUtil.getUserProperties(username);
//        UserContext userCtx = new UserContext(username, org, properties, roles);
//        try {
//            PublisherAPISearchResult searchAPIs = apiPersistenceInstance.searchAPIsForPublisher(org, "", 0,
//                    Integer.MAX_VALUE, userCtx);
//
//            if (searchAPIs != null) {
//                List<PublisherAPIInfo> list = searchAPIs.getPublisherAPIInfoList();
//                for (PublisherAPIInfo publisherAPIInfo : list) {
//                    API mappedAPI = APIMapper.INSTANCE.toApi(publisherAPIInfo);
//                    apiSortedList.add(mappedAPI);
//                }
//            }
//        } catch (APIPersistenceException e) {
//            throw new APIManagementException("Error while searching the api ", e);
//        }
//
//        Collections.sort(apiSortedList, new APINameComparator());
//        return apiSortedList;
//    }
}
