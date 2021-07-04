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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.apim.monetization.impl.model.MonetizationPlatformCustomer;
import org.wso2.apim.monetization.impl.model.MonetizationSharedCustomer;
import org.wso2.apim.monetization.impl.model.MonetizedSubscription;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.policy.SubscriptionPolicy;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * This class is used to handle database related actions when configuring monetization with zarinpal
 */
public class ZarinpalMonetizationDAO {

    private static final Log log = LogFactory.getLog(ZarinpalMonetizationDAO.class);
    private ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
    private static ZarinpalMonetizationDAO INSTANCE = null;

    /**
     * Method to get the instance of the ZarinpalMonetizationDAO.
     *
     * @return {@link ZarinpalMonetizationDAO} instance
     */
    public static ZarinpalMonetizationDAO getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ZarinpalMonetizationDAO();
        }
        return INSTANCE;
    }

    /**
     * Add monetization product to the database
     *
     * @param product    the product
     * @throws ProductMonetizationException if failed to add monetization product data to the database
     */
    public void addMonetizationProduct(Product product)
            throws ProductMonetizationException {
        Connection conn = null;
        PreparedStatement policyStatement = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            policyStatement = conn.prepareStatement(ZarinpalMonetizationConstants.INSERT_MONETIZATION_PRODUCT_SQL);
            policyStatement.setString(1, product.getId());
            policyStatement.setString(2, product.getName());
            policyStatement.setString(3, product.getType());
            policyStatement.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    String errorMessage = "Failed to rollback adding monetization product for : " + product.getName();
                    log.error(errorMessage);
                    throw new ProductMonetizationException(errorMessage, ex);
                }
            }
            String errorMessage = "Failed to add monetization product for : " + product.getName();
            log.error(errorMessage);
            throw new ProductMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(policyStatement, conn, null);
        }
    }

    /**
     * Delete monetization product from the database
     *
     * @param productId    the product id
     * @throws ProductMonetizationException if failed to delete monetization product from the database
     */
    public void deleteMonetizationProduct(String productId)
            throws ProductMonetizationException {
        Connection conn = null;
        PreparedStatement policyStatement = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            policyStatement = conn.prepareStatement(ZarinpalMonetizationConstants.DELETE_MONETIZATION_PRODUCT_SQL);
            policyStatement.setString(1, productId);
            policyStatement.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    String errorMessage = "Failed to delete monetization product : " + productId;
                    log.error(errorMessage);
                    throw new ProductMonetizationException(errorMessage, ex);
                }
            }
            String errorMessage = "Failed to delete monetization product : " + productId;
            log.error(errorMessage);
            throw new ProductMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(policyStatement, conn, null);
        }
    }

    /**
     * Get monetization plan from the database
     *
     * @param planId    the plan id
     * @throws ProductMonetizationException if failed to get monetization plan from the database
     */
    public Plan getMonetizationPlan(String planId)
            throws ProductMonetizationException {
        Connection conn = null;
        ResultSet rs = null;
        PreparedStatement policyStatement = null;
        Plan plan = new Plan();
        plan.setId(null);
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            policyStatement = conn.prepareStatement(ZarinpalMonetizationConstants.GET_MONETIZATION_PLAN_SQL);
            policyStatement.setString(1, planId);
            rs = policyStatement.executeQuery();
            while (rs.next()) {
                plan.setId(rs.getString("ID"));
                plan.setCurrency(rs.getString("CURRENCY"));
                plan.setProductId(rs.getString("PRODUCT_ID"));
                plan.setProductNickname(rs.getString("PRODUCT_NICKNAME"));
                plan.setInterval(rs.getString("INTERVAL"));
                plan.setAmount(rs.getInt("AMOUNT"));
                plan.setUsageType(rs.getString("USAGE_TYPE"));
            }
        } catch (SQLException e) {
            String errorMessage = "Failed to get monetization plan : " + planId;
            log.error(errorMessage);
            throw new ProductMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(policyStatement, conn, rs);
        }
        return plan;
    }

    /**
     * Add monetization plan data to the database
     *
     * @param plan    the plan
     * @throws ProductMonetizationException if failed to add monetization plan to the database
     */
    public void addMonetizationPlan(Plan plan)
            throws ProductMonetizationException {
        Connection conn = null;
        PreparedStatement policyStatement = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            policyStatement = conn.prepareStatement(ZarinpalMonetizationConstants.INSERT_MONETIZATION_PLAN_SQL);
            policyStatement.setString(1, plan.getId());
            policyStatement.setString(2, plan.getCurrency());
            policyStatement.setString(3, plan.getProductId());
            policyStatement.setString(4, plan.getProductNickname());
            policyStatement.setString(5, plan.getInterval());
            policyStatement.setInt(6, plan.getAmount());
            policyStatement.setString(7, plan.getUsageType());
            policyStatement.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    String errorMessage = "Failed to rollback adding monetization plan for (This is a test message!) : " + plan.getProductNickname();
                    log.error(errorMessage);
                    log.error(ex.getMessage());
                    throw new ProductMonetizationException(errorMessage, ex);
                }
            }
            String errorMessage = "Failed to add monetization plan for (This is a test message!) : " + plan.getProductNickname();
            log.error(errorMessage);
            throw new ProductMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(policyStatement, conn, null);
        }
    }

    /**
     * Delete monetization plan to the database
     *
     * @param planId    the plan id
     * @throws ProductMonetizationException if failed to delete monetization plan from the database
     */
    public void deleteMonetizationPlan(String planId)
            throws ProductMonetizationException {
        Connection conn = null;
        PreparedStatement policyStatement = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            policyStatement = conn.prepareStatement(ZarinpalMonetizationConstants.DELETE_MONETIZATION_PLAN_SQL);
            policyStatement.setString(1, planId);
            policyStatement.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    String errorMessage = "Failed to delete monetization plan : " + planId;
                    log.error(errorMessage);
                    throw new ProductMonetizationException(errorMessage, ex);
                }
            }
            String errorMessage = "Failed to delete monetization product : " + planId;
            log.error(errorMessage);
            throw new ProductMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(policyStatement, conn, null);
        }
    }

    /**
     * Add monetization plan data to the database
     *
     * @param policy    subscription policy
     * @param productId product id (in the billing engine)
     * @param planId    plan id (in the billing engine)
     * @throws ZarinpalMonetizationException if failed to add monetization plan data to the database
     */
    public void addMonetizationPlanData(SubscriptionPolicy policy, String productId, String planId)
            throws ZarinpalMonetizationException {

        Connection conn = null;
        PreparedStatement policyStatement = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            policyStatement = conn.prepareStatement(ZarinpalMonetizationConstants.INSERT_MONETIZATION_PLAN_DATA_SQL);
            policyStatement.setString(1, apiMgtDAO.getSubscriptionPolicy(policy.getPolicyName(),
                    policy.getTenantId()).getUUID());
            policyStatement.setString(2, productId);
            policyStatement.setString(3, planId);
            policyStatement.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    String errorMessage = "Failed to rollback adding monetization plan for : " + policy.getPolicyName();
                    log.error(errorMessage);
                    throw new ZarinpalMonetizationException(errorMessage, ex);
                }
            }
            String errorMessage = "Failed to add monetization plan for : " + policy.getPolicyName();
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get subscription policy : " + policy.getPolicyName() +
                    " from database when creating zarinpal plan.";
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(policyStatement, conn, null);
        }
    }

    /**
     * Get plan data (in billing engine) for a given subscription policy
     *
     * @param policy subscription policy
     * @return plan data of subscription policy
     * @throws ZarinpalMonetizationException if failed to get plan data
     */
    public Map<String, String> getPlanData(SubscriptionPolicy policy) throws ZarinpalMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        Map<String, String> planData = new HashMap<String, String>();
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(ZarinpalMonetizationConstants.GET_BILLING_PLAN_DATA);
            ps.setString(1, apiMgtDAO.getSubscriptionPolicy(policy.getPolicyName(), policy.getTenantId()).getUUID());
            rs = ps.executeQuery();
            while (rs.next()) {
                planData.put(ZarinpalMonetizationConstants.PRODUCT_ID, rs.getString("PRODUCT_ID"));
                planData.put(ZarinpalMonetizationConstants.PLAN_ID, rs.getString("PLAN_ID"));
            }
        } catch (SQLException e) {
            String errorMessage = "Error while getting plan data for : " + policy.getPolicyName() + " policy.";
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get subscription policy : " + policy.getPolicyName() +
                    " when getting plan data.";
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return planData;
    }

    /**
     * Update monetization plan data in the database
     *
     * @param policy    subscription policy
     * @param productId product id (in the billing engine)
     * @param planId    plan id (in the billing engine)
     * @throws ZarinpalMonetizationException if failed to update monetization plan data to the database
     */
    public void updateMonetizationPlanData(SubscriptionPolicy policy, String productId, String planId)
            throws ZarinpalMonetizationException {

        Connection conn = null;
        PreparedStatement policyStatement = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            policyStatement = conn.prepareStatement(ZarinpalMonetizationConstants.UPDATE_MONETIZATION_PLAN_ID_SQL);
            policyStatement.setString(1, planId);
            policyStatement.setString(2, apiMgtDAO.getSubscriptionPolicy(policy.getPolicyName(),
                    policy.getTenantId()).getUUID());
            policyStatement.setString(3, productId);
            policyStatement.execute();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    String errorMessage = "Failed to rollback the update monetization plan action for policy : " +
                            policy.getPolicyName();
                    log.error(errorMessage);
                    throw new ZarinpalMonetizationException(errorMessage, ex);
                }
            }
            String errorMessage = "Failed to update monetization plan for policy: " + policy;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get subscription policy : " + policy.getPolicyName() +
                    " when updating monetization plan data.";
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);

        } finally {
            APIMgtDBUtil.closeAllConnections(policyStatement, conn, null);
        }
    }

    /**
     * Delete monetization plan data from the database
     *
     * @param policy subscription policy
     * @throws ZarinpalMonetizationException if failed to delete monetization plan data from the database
     */
    public void deleteMonetizationPlanData(SubscriptionPolicy policy) throws ZarinpalMonetizationException {

        Connection conn = null;
        PreparedStatement policyStatement = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            policyStatement = conn.prepareStatement(ZarinpalMonetizationConstants.DELETE_MONETIZATION_PLAN_DATA);
            policyStatement.setString(1, apiMgtDAO.getSubscriptionPolicy(policy.getPolicyName(),
                    policy.getTenantId()).getUUID());
            policyStatement.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    String errorMessage = "Failed to rollback the delete monetization plan action for policy : " +
                            policy.getPolicyName();
                    log.error(errorMessage);
                    throw new ZarinpalMonetizationException(errorMessage, ex);
                }
            }
            String errorMessage = "Failed to delete the monetization plan action for policy : " + policy.getPolicyName();
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get policy : " + policy.getPolicyName() +
                    " when deleting monetization plan.";
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(policyStatement, conn, null);
        }
    }

    /**
     * This method is used to get the product id in the billing engine for a give API
     *
     * @param apiId API ID
     * @return billing engine product ID of the give API
     */
    public String getBillingEngineProductId(int apiId) throws ZarinpalMonetizationException {

        String billingEngineProductId = null;
        Connection connection = null;
        PreparedStatement statement = null;
        try {
            connection = APIMgtDBUtil.getConnection();
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(ZarinpalMonetizationConstants.GET_BILLING_ENGINE_PRODUCT_BY_API);
            statement.setInt(1, apiId);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                billingEngineProductId = rs.getString("STRIPE_PRODUCT_ID");
            }
            connection.commit();
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing engine product ID of API : " + apiId;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(statement, connection, null);
        }
        return billingEngineProductId;
    }

    /**
     * Get billing plan ID for a given tier
     *
     * @param apiID    API ID
     * @param tierName tier name
     * @return billing plan ID for a given tier
     * @throws ZarinpalMonetizationException if failed to get billing plan ID for a given tier
     */
    public String getBillingEnginePlanIdForTier(int apiID, String tierName) throws ZarinpalMonetizationException {

        Connection connection = null;
        PreparedStatement statement = null;
        String billingEnginePlanId = StringUtils.EMPTY;
        try {
            connection = APIMgtDBUtil.getConnection();
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(ZarinpalMonetizationConstants.GET_BILLING_PLAN_FOR_TIER);
            statement.setInt(1, apiID);
            statement.setString(2, tierName);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                billingEnginePlanId = rs.getString("STRIPE_PLAN_ID");
            }
            connection.commit();
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing plan ID tier : " + tierName;
            log.error(errorMessage, e);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(statement, connection, null);
        }
        return billingEnginePlanId;
    }

    /**
     * This method is used to add monetization data to the DB
     *
     * @param apiId       API ID
     * @param productId   zarinpal product ID
     * @param tierPlanMap zarinpal plan and tier mapping
     * @throws ZarinpalMonetizationException if failed to add monetization data to the DB
     */
    public void addMonetizationData(int apiId, String productId, Map<String, String> tierPlanMap)
            throws ZarinpalMonetizationException {

        PreparedStatement preparedStatement = null;
        Connection connection = null;
        boolean initialAutoCommit = false;
        try {
            if (!tierPlanMap.isEmpty()) {
                connection = APIMgtDBUtil.getConnection();
                preparedStatement = connection.prepareStatement(ZarinpalMonetizationConstants.ADD_MONETIZATION_DATA_SQL);
                initialAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                for (Map.Entry<String, String> entry : tierPlanMap.entrySet()) {
                    preparedStatement.setInt(1, apiId);
                    preparedStatement.setString(2, entry.getKey());
                    preparedStatement.setString(3, productId);
                    preparedStatement.setString(4, entry.getValue());
                    preparedStatement.addBatch();
                }
                preparedStatement.executeBatch();
                connection.commit();
            }
        } catch (SQLException e) {
            try {
                if (connection != null) {
                    connection.rollback();
                }
            } catch (SQLException ex) {
                String errorMessage = "Failed to rollback add monetization data for API : " + apiId;
                log.error(errorMessage, e);
                throw new ZarinpalMonetizationException(errorMessage, e);
            } finally {
                APIMgtDBUtil.setAutoCommit(connection, initialAutoCommit);
            }
        } finally {
            APIMgtDBUtil.closeAllConnections(preparedStatement, connection, null);
        }
    }

    /**
     * Get billing plan ID for a given tier
     *
     * @param tierUUID tier UUID
     * @return billing plan ID for a given tier
     * @throws ZarinpalMonetizationException if failed to get billing plan ID for a given tier
     */
    public String getBillingPlanId(String tierUUID) throws ZarinpalMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        String planId = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(ZarinpalMonetizationConstants.GET_BILLING_PLAN_ID);
            ps.setString(1, tierUUID);
            rs = ps.executeQuery();
            while (rs.next()) {
                planId = rs.getString("PLAN_ID");
            }
        } catch (SQLException e) {
            String errorMessage = "Error while getting zarinpal plan ID for tier UUID : " + tierUUID;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return planId;
    }

    /**
     * Get subscription UUID given the subscription ID
     *
     * @param subscriptionId subscription ID
     * @return subscription UUID
     * @throws ZarinpalMonetizationException if failed to get subscription UUID given the subscription ID
     */
    public String getSubscriptionUUID(int subscriptionId) throws ZarinpalMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        String planId = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(ZarinpalMonetizationConstants.GET_SUBSCRIPTION_UUID);
            ps.setInt(1, subscriptionId);
            rs = ps.executeQuery();
            while (rs.next()) {
                planId = rs.getString("UUID");
            }
        } catch (SQLException e) {
            String errorMessage = "Error while getting UUID of subscription ID : " + subscriptionId;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return planId;
    }

    /**
     * This method is used to get zarinpal plan and tier mapping
     *
     * @param apiID           API ID
     * @param zarinpalProductId zarinpal product ID
     * @return mapping between tier and zarinpal plans
     * @throws ZarinpalMonetizationException if failed to get mapping between tier and zarinpal plans
     */
    public Map<String, String> getTierToBillingEnginePlanMapping(int apiID, String zarinpalProductId)
            throws ZarinpalMonetizationException {

        Map<String, String> zarinpalPlanTierMap = new HashMap<String, String>();
        Connection connection = null;
        PreparedStatement statement = null;
        try {
            connection = APIMgtDBUtil.getConnection();
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(ZarinpalMonetizationConstants.GET_BILLING_PLANS_BY_PRODUCT);
            statement.setInt(1, apiID);
            statement.setString(2, zarinpalProductId);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                String tierName = rs.getString("TIER_NAME");
                String zarinpalPlanId = rs.getString("STRIPE_PLAN_ID");
                zarinpalPlanTierMap.put(tierName, zarinpalPlanId);
            }
            connection.commit();
        } catch (SQLException e) {
            String errorMessage = "Failed to get zarinpal plan and tier mapping for API : " + apiID;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(statement, connection, null);
        }
        return zarinpalPlanTierMap;
    }

    /**
     * This method deletes monetization data for a given API from the DB
     *
     * @param apiId API ID
     * @throws ZarinpalMonetizationException if failed to delete monetization data
     */
    public void deleteMonetizationData(int apiId) throws ZarinpalMonetizationException {

        Connection connection = null;
        PreparedStatement statement = null;
        boolean initialAutoCommit = false;
        try {
            connection = APIMgtDBUtil.getConnection();
            statement = connection.prepareStatement(ZarinpalMonetizationConstants.DELETE_MONETIZATION_DATA_SQL);
            initialAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            statement.setInt(1, apiId);
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            try {
                if (connection != null) {
                    connection.rollback();
                }
            } catch (SQLException ex) {
                String errorMessage = "Failed to delete monetization data for API : " + apiId;
                log.error(errorMessage);
                throw new ZarinpalMonetizationException(errorMessage, e);
            } finally {
                APIMgtDBUtil.setAutoCommit(connection, initialAutoCommit);
            }
        } finally {
            APIMgtDBUtil.closeAllConnections(statement, connection, null);
        }
    }

    /**
     * Get Billing Engine Subscription ID
     *
     * @param apiId         API ID
     * @param applicationId Application ID
     * @return Billing Engine Subscription ID
     * @throws ZarinpalMonetizationException If Failed To Get Billing Engine Subscription ID
     */
    public String getBillingEngineSubscriptionId(int apiId, int applicationId) throws ZarinpalMonetizationException {

        String billingEngineSubscriptionId = null;
        Connection connection = null;
        PreparedStatement statement = null;
        try {
            connection = APIMgtDBUtil.getConnection();
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(ZarinpalMonetizationConstants.GET_BILLING_ENGINE_SUBSCRIPTION_ID);
            statement.setInt(1, applicationId);
            statement.setInt(2, apiId);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                billingEngineSubscriptionId = rs.getString("SUBSCRIPTION_ID");
            }
            connection.commit();
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing engine subscription ID of API : " + apiId +
                    " and application ID : " + applicationId;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(statement, connection, null);
        }
        return billingEngineSubscriptionId;
    }

    /**
     * Add billing engine platform customers info
     *
     * @param subscriberId Subscriber's Id
     * @param tenantId     Id of tenant
     * @param customerId   Id of the customer created in zarinpal
     * @return Id of the customer record in the database
     * @throws ZarinpalMonetizationException If failed to add billing engine customer details
     */
    public int addBEPlatformCustomer(int subscriberId, int tenantId, String customerId) throws
            ZarinpalMonetizationException {

        Connection conn = null;
        ResultSet rs = null;
        PreparedStatement ps = null;
        int id = 0;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            String query = ZarinpalMonetizationConstants.ADD_BE_PLATFORM_CUSTOMER_SQL;
            ps = conn.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, subscriberId);
            ps.setInt(2, tenantId);
            ps.setString(3, customerId);
            ps.executeUpdate();
            ResultSet set = ps.getGeneratedKeys();
            if (set.next()) {
                id = set.getInt(1);
            } else {
                String errorMessage = "Failed to get ID of the monetized subscription. Subscriber ID : " +
                        subscriberId + " , tenant ID : " + tenantId + " , customer ID : " + customerId;
                throw new ZarinpalMonetizationException(errorMessage);
            }
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    log.error("Error while rolling back the failed operation", ex);
                }
            }
            String errorMessage = "Failed to add Zarinpal platform customer details for Subscriber : " + subscriberId;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return id;
    }

    /**
     * Add Billing Engine Shared Customer info
     *
     * @param sharedCustomer object with Billing Engine Shared customer info
     * @return Id of the customer record in the database
     * @throws ZarinpalMonetizationException If Failed To add Billing Engine Shared Customer details
     */
    public int addBESharedCustomer(MonetizationSharedCustomer sharedCustomer) throws ZarinpalMonetizationException {

        Connection conn = null;
        ResultSet rs = null;
        PreparedStatement ps = null;
        int id = 0;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            String query = ZarinpalMonetizationConstants.ADD_BE_SHARED_CUSTOMER_SQL;
            ps = conn.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, sharedCustomer.getApplicationId());
            ps.setString(2, sharedCustomer.getApiProvider());
            ps.setInt(3, sharedCustomer.getTenantId());
            ps.setString(4, sharedCustomer.getSharedCustomerId());
            ps.setInt(5, sharedCustomer.getParentCustomerId());
            ps.executeUpdate();
            ResultSet set = ps.getGeneratedKeys();
            if (set.next()) {
                id = set.getInt(1);
            } else {
                String errorMessage = "Failed to set ID of the shared customer : " + sharedCustomer.getId() +
                        " , tenant ID : " + sharedCustomer.getTenantId() + " , application ID : " +
                        sharedCustomer.getApplicationId();
                throw new ZarinpalMonetizationException(errorMessage);
            }
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    log.error("Error while rolling back the failed operation", ex);
                }
            }
            String errorMessage = "Failed to add info of billing engine shared customer created"
                    + " for Application with ID :" + sharedCustomer.getApplicationId()
                    + " under Provider : " + sharedCustomer.getApiProvider();
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return id;
    }

    /**
     * Create Billing Engine Subscription Info
     *
     * @param identifier       API identifier
     * @param applicationId    Id of the Application
     * @param tenandId         Id of the tenant
     * @param sharedCustomerId Id of the shared customer
     * @param subscriptionId   Id of the Billing Engine Subscriptions
     * @return Id of the customer record in the database
     * @throws ZarinpalMonetizationException If Failed To add Billing Engine Shared Customer details
     */
    public void addBESubscription(APIIdentifier identifier, int applicationId, int tenandId,
                                  int sharedCustomerId, String subscriptionId, String planId) throws ZarinpalMonetizationException {

        Connection conn = null;
        ResultSet rs = null;
        PreparedStatement ps = null;
        int apiId;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            try {
                apiId = apiMgtDAO.getAPIID(identifier, conn);
            } catch (APIManagementException e) {
                String errorMessage = "Failed to get the ID of the API " + identifier.getApiName();
                log.error(errorMessage);
                throw new ZarinpalMonetizationException(errorMessage, e);
            }
            String query = ZarinpalMonetizationConstants.ADD_BE_SUBSCRIPTION_SQL;
            ps = conn.prepareStatement(query);
            ps.setInt(1, apiId);
            ps.setInt(2, applicationId);
            ps.setInt(3, tenandId);
            ps.setInt(4, sharedCustomerId);
            ps.setString(5, subscriptionId);
            ps.setString(6, planId);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    log.error("Error while rolling back the failed operation", ex);
                }
            }
            String errorMessage = "Failed to add Zarinpal subscription info for API : " + identifier.getApiName() + " by"
                    + " Application : " + applicationId;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
    }

    /**
     * Get Billing Engine Platform Customer Info
     *
     * @param subscriberId Id of the Subscriber
     * @param tenantId     Id of the tenant
     * @return MonetizationPlatformCustomer info of Billing Engine Platform Customer
     * @throws ZarinpalMonetizationException If Failed To get Billing Engine Platform Customer details
     */
    public MonetizationPlatformCustomer getPlatformCustomer(int subscriberId, int tenantId) throws
            ZarinpalMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet result = null;
        MonetizationPlatformCustomer monetizationPlatformCustomer = new MonetizationPlatformCustomer();
        String sqlQuery = ZarinpalMonetizationConstants.GET_BE_PLATFORM_CUSTOMER_SQL;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(sqlQuery);
            ps.setInt(1, subscriberId);
            ps.setInt(2, tenantId);
            result = ps.executeQuery();
            if (result.next()) {
                monetizationPlatformCustomer.setId(result.getInt("ID"));
                monetizationPlatformCustomer.setCustomerId(result.getString("CUSTOMER_ID"));
            }
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing engine platform customer details for Subscriber : " +
                    subscriberId;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, result);
        }
        return monetizationPlatformCustomer;
    }

    /**
     * Get Billing Engine Shared Customer Info
     *
     * @param applicationId Id of the Application
     * @param apiProvider   api provider
     * @param tenantId      Id of the tenant
     * @return MonetizationPlatformCustomer info of Billing Engine Shared Customer
     * @throws ZarinpalMonetizationException If Failed To get Billing Engine Platform Shared details
     */
    public MonetizationSharedCustomer getSharedCustomer(int applicationId, String apiProvider,
                                                        int tenantId) throws ZarinpalMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet result = null;
        MonetizationSharedCustomer monetizationSharedCustomer = new MonetizationSharedCustomer();
        String sqlQuery = ZarinpalMonetizationConstants.GET_BE_SHARED_CUSTOMER_SQL;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(sqlQuery);
            ps.setInt(1, applicationId);
            ps.setString(2, apiProvider);
            ps.setInt(3, tenantId);
            result = ps.executeQuery();
            if (result.next()) {
                monetizationSharedCustomer.setId(result.getInt("ID"));
                monetizationSharedCustomer.setSharedCustomerId(result.getString("SHARED_CUSTOMER_ID"));
            }
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing Engine Shared Customer details for application with ID : " +
                    applicationId;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, result);
        }
        return monetizationSharedCustomer;
    }

    /**
     * Remove billing engine subscription info
     *
     * @param id Id of the Subscription Info
     * @throws ZarinpalMonetizationException If failed to delete subscription details
     */
    public void removeMonetizedSubscription(int id) throws ZarinpalMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet result = null;
        String sqlQuery = ZarinpalMonetizationConstants.DELETE_BE_SUBSCRIPTION_SQL;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(sqlQuery);
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            String errorMessage = "Failed to remove monetization info from DB of subscription with ID : " + id;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, result);
        }
    }

    /**
     * Get billing engine Subscription info
     *
     * @param apiName       api name
     * @param apiVersion    api version
     * @param apiProvider   api provider
     * @param applicationId Id of the Application
     * @param tenantDomain  tenant domain
     * @return MonetizationSubscription info of Billing Engine Subscription
     * @throws ZarinpalMonetizationException If Failed To get Billing Engine Subscription details
     */
    public MonetizedSubscription getMonetizedSubscription(String apiName, String apiVersion, String apiProvider,
                                                          int applicationId, String tenantDomain)
            throws ZarinpalMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet result = null;
        int apiId;
        MonetizedSubscription monetizedSubscription = new MonetizedSubscription();
        int tenantId = APIUtil.getTenantIdFromTenantDomain(tenantDomain);
        APIIdentifier identifier = new APIIdentifier(apiProvider, apiName, apiVersion);
        String sqlQuery = ZarinpalMonetizationConstants.GET_BE_SUBSCRIPTION_SQL;
        try {
            conn = APIMgtDBUtil.getConnection();
            try {
                apiId = apiMgtDAO.getAPIID(identifier, conn);
            } catch (APIManagementException e) {
                String errorMessgae = "Failed to get ID for API : " + apiName;
                log.error(errorMessgae);
                throw new ZarinpalMonetizationException(errorMessgae, e);
            }
            ps = conn.prepareStatement(sqlQuery);
            ps.setInt(1, applicationId);
            ps.setInt(2, apiId);
            ps.setInt(3, tenantId);
            result = ps.executeQuery();
            if (result.next()) {
                monetizedSubscription.setId(result.getInt("ID"));
                monetizedSubscription.setSubscriptionId(result.getString("SUBSCRIPTION_ID"));
            }
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing engine Subscription info for API : " + apiName;
            log.error(errorMessage);
            throw new ZarinpalMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, result);
        }
        return monetizedSubscription;
    }
}
