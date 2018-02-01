/*
 *  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.carbon.user.core.common;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.privacy.DefaultIdManager;
import org.wso2.carbon.privacy.IdManager;
import org.wso2.carbon.privacy.PrivacyInsulator;
import org.wso2.carbon.privacy.exception.IdManagerException;
import org.wso2.carbon.user.api.Authentication;
import org.wso2.carbon.user.api.RealmConfiguration;
import org.wso2.carbon.user.api.User;
import org.wso2.carbon.user.core.Permission;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.UserStoreConfigConstants;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.authorization.AuthorizationCache;
import org.wso2.carbon.user.core.claim.Claim;
import org.wso2.carbon.user.core.claim.ClaimManager;
import org.wso2.carbon.user.core.claim.ClaimMapping;
import org.wso2.carbon.user.core.dto.RoleDTO;
import org.wso2.carbon.user.core.hybrid.HybridRoleManager;
import org.wso2.carbon.user.core.internal.UMListenerServiceComponent;
import org.wso2.carbon.user.core.jdbc.JDBCUserStoreManager;
import org.wso2.carbon.user.core.ldap.LDAPConstants;
import org.wso2.carbon.user.core.listener.SecretHandleableListener;
import org.wso2.carbon.user.core.listener.UserOperationEventListener;
import org.wso2.carbon.user.core.listener.UserStoreManagerConfigurationListener;
import org.wso2.carbon.user.core.listener.UserStoreManagerListener;
import org.wso2.carbon.user.core.model.UserImpl;
import org.wso2.carbon.user.core.profile.ProfileConfigurationManager;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.system.SystemUserRoleManager;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.Secret;
import org.wso2.carbon.utils.UnsupportedSecretTypeException;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.lang.reflect.Constructor;
import java.nio.CharBuffer;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

public abstract class AbstractUserStoreManager extends AbstractSecuredEntityManager implements UserStoreManager {

    private static Log log = LogFactory.getLog(AbstractUserStoreManager.class);

    protected static final String FALSE_VALUE = "false";
    protected static final String TRUE_VALUE = "true";

    private static final String MAX_LIST_LENGTH = "100";
    private static final int MAX_ITEM_LIMIT_UNLIMITED = -1;
    private static final String MULIPLE_ATTRIBUTE_ENABLE = "MultipleAttributeEnable";
    private static final String DISAPLAY_NAME_CLAIM = "http://wso2.org/claims/displayName";
    private static final String SCIM_USERNAME_CLAIM_URI = "urn:scim:schemas:core:1.0:userName";
    private static final String USERNAME_CLAIM_URI = "http://wso2.org/claims/username";
    private static final String APPLICATION_DOMAIN = "Application";
    private static final String WORKFLOW_DOMAIN = "Workflow";
    private static final String USER_NOT_FOUND = "UserNotFound";
    private static final String EXISTING_USER = "UserAlreadyExisting";
    private static final String EXISTING_ROLE = "RoleExisting";
    private static final String READ_ONLY_STORE = "ReadOnlyUserStoreManager";
    private static final String READ_ONLY_PRIMARY_STORE = "ReadOnlyPrimaryUserStoreManager";
    private static final String INVALID_ROLE = "InvalidRole";
    private static final String ANONYMOUS_USER = "AnonymousUser";
    private static final String INVALID_OPERATION = "InvalidOperation";
    private static final String NO_READ_WRITE_PERMISSIONS = "NoReadWritePermission";
    private static final String SHARED_USER_ROLES = "SharedUserRoles";
    private static final String REMOVE_ADMIN_USER = "RemoveAdminUser";
    private static final String LOGGED_IN_USER = "LoggedInUser";
    private static final String ADMIN_USER = "AdminUser";
    private static final String INVALID_PASSWORD = "PasswordInvalid";
    private static final String PROPERTY_PASSWORD_ERROR_MSG = "PasswordJavaRegExViolationErrorMsg";
    private static final String MULTI_ATTRIBUTE_SEPARATOR = "MultiAttributeSeparator";

    protected int tenantId;
    protected DataSource dataSource = null;
    protected ClaimManager claimManager = null;
    protected UserRealm userRealm = null;
    protected HybridRoleManager hybridRoleManager = null;

    // User roles cache
    protected SystemUserRoleManager systemUserRoleManager = null;
    protected boolean readGroupsEnabled = false;
    protected boolean writeGroupsEnabled = false;
    private UserStoreManager secondaryUserStoreManager;
    private boolean replaceEscapeCharactersAtUserLogin = true;
    private Map<String, UserStoreManager> userStoreManagerHolder = new HashMap<>();
    private Map<String, Integer> maxUserListCount = null;
    private Map<String, Integer> maxRoleListCount = null;
    private List<UserStoreManagerConfigurationListener> listener = new ArrayList<>();
    private IdManager idManager = null;

    /**
     * This method is used by the support system to read properties
     */
    @Deprecated
    protected abstract Map<String, String> getUserPropertyValues(String userName, String[] propertyNames,
                                                                 String profileName) throws UserStoreException;

    /**
     * This method is used by the support system to read properties
     */
    protected Map<String, String> getUserPropertyValues(User user, String[] propertyNames, String profileName)
            throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }

    /**
     * Check for existing roles.
     *
     * @param roleName name of the role.
     * @return True if role exist.
     */
    protected abstract boolean doCheckExistingRole(String roleName) throws UserStoreException;

    /**
     * Creates the search base and other relevant parameters for the provided role name
     *
     * @param roleName Name of the role.
     * @return Role context.
     */
    protected abstract RoleContext createRoleContext(String roleName) throws UserStoreException;

    /**
     * Check for existing users.
     *
     * @param userName User name of the user.
     * @return True if user exist.
     * @throws UserStoreException Error while doing the operation.
     */
    @Deprecated
    protected abstract boolean doCheckExistingUser(String userName) throws UserStoreException;

    /**
     * Check for existing users.
     *
     * @param user User object.
     * @return True if user exist.
     * @throws UserStoreException Error while doing the operation.
     */
    protected boolean doCheckExistingUser(User user) throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }

    /**
     * Retrieves a list of user names for given user's property in user profile
     *
     * @param property    user property in user profile
     * @param value       value of property
     * @param profileName profile name, can be null. If null the default profile is considered.
     * @return An array of user names
     * @throws UserStoreException if the operation failed
     */
    protected abstract String[] getUserListFromProperties(String property, String value,
                                                          String profileName) throws UserStoreException;

    /**
     * Given the user name and a credential object, the implementation code must validate whether
     * the user is authenticated.
     *
     * @param userName   The user name
     * @param credential The credential of a user
     * @return If the value is true the provided credential match with the user name. False is
     * returned for invalid credential, invalid user name and mismatching credential with
     * user name.
     * @throws UserStoreException An unexpected exception has occurred
     */
    @Deprecated
    protected abstract boolean doAuthenticate(String userName, Object credential) throws UserStoreException;

    /**
     * Given the user name and a credential object, the implementation code must validate whether
     * the user is authenticated.
     *
     * @param user   The user object.
     * @param credential The credential of a user
     * @return If the value is true the provided credential match with the user name. False is
     * returned for invalid credential, invalid user name and mismatching credential with
     * user name.
     * @throws UserStoreException An unexpected exception has occurred
     */
    protected boolean doAuthenticate(User user, Object credential) throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }


    /**
     * Add a user to the user store.
     *
     * @param userName              User name of the user
     * @param credential            The credential/password of the user
     * @param roleList              The roles that user belongs
     * @param claims                Properties of the user
     * @param profileName           profile name, can be null. If null the default profile is considered.
     * @param requirePasswordChange whether password required is need
     * @throws UserStoreException An unexpected exception has occurred
     */
    @Deprecated
    protected abstract void doAddUser(String userName, Object credential, String[] roleList, Map<String, String> claims,
                                      String profileName, boolean requirePasswordChange)
            throws UserStoreException;

    /**
     * Add a user to the user store.
     *
     * @param user                  User object.
     * @param credential            The credential/password of the user
     * @param roleList              The roles that user belongs
     * @param claims                Properties of the user
     * @param profileName           profile name, can be null. If null the default profile is considered.
     * @param requirePasswordChange whether password required is need
     * @throws UserStoreException An unexpected exception has occurred
     */
    protected void doAddUser(User user, Object credential, String[] roleList, Map<String, String> claims,
                                      String profileName, boolean requirePasswordChange)
            throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }

    /**
     * Update the credential/password of the user
     *
     * @param userName      The user name
     * @param newCredential The new credential/password
     * @param oldCredential The old credential/password
     * @throws UserStoreException An unexpected exception has occurred
     */
    @Deprecated
    protected abstract void doUpdateCredential(String userName, Object newCredential, Object oldCredential)
            throws UserStoreException;

    /**
     * Update the credential/password of the user
     *
     * @param user          The user object.
     * @param newCredential The new credential/password
     * @param oldCredential The old credential/password
     * @throws UserStoreException An unexpected exception has occurred
     */
    protected void doUpdateCredential(User user, Object newCredential, Object oldCredential)
            throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }

    /**
     * Update credential/password by the admin of another user
     *
     * @param userName      The user name
     * @param newCredential The new credential
     * @throws UserStoreException An unexpected exception has occurred
     */
    @Deprecated
    protected abstract void doUpdateCredentialByAdmin(String userName, Object newCredential) throws UserStoreException;

    /**
     * Update credential/password by the admin of another user
     *
     * @param user          The user object.
     * @param newCredential The new credential
     * @throws UserStoreException An unexpected exception has occurred
     */
     protected void doUpdateCredentialByAdmin(User user, Object newCredential) throws UserStoreException {
         throw new UnsupportedOperationException("Method should be overridden in the child class.");
     }

    /**
     * Delete the user with the given user name
     *
     * @param userName The user name
     * @throws UserStoreException An unexpected exception has occurred
     */
    @Deprecated
    protected abstract void doDeleteUser(String userName) throws UserStoreException;

    /**
     * Delete the user with the given user name
     *
     * @param user The object.
     * @throws UserStoreException An unexpected exception has occurred
     */
    protected void doDeleteUser(User user) throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }

    /**
     * Set a single user claim value
     *
     * @param userName    The user name
     * @param claimURI    The claim URI
     * @param claimValue  The value
     * @param profileName The profile name, can be null. If null the default profile is considered.
     * @throws UserStoreException An unexpected exception has occurred
     */
    @Deprecated
    protected abstract void doSetUserClaimValue(String userName, String claimURI, String claimValue, String profileName)
            throws UserStoreException;

    /**
     * Set a single user claim value
     *
     * @param user        The user object.
     * @param claimURI    The claim URI
     * @param claimValue  The value
     * @param profileName The profile name, can be null. If null the default profile is considered.
     * @throws UserStoreException An unexpected exception has occurred
     */
    protected void doSetUserClaimValue(User user, String claimURI, String claimValue, String profileName)
            throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }

    /**
     * Set many user claim values
     *
     * @param userName    The user name
     * @param claims      Map of claim URIs against values
     * @param profileName The profile name, can be null. If null the default profile is considered.
     * @throws UserStoreException An unexpected exception has occurred
     */
    @Deprecated
    protected abstract void doSetUserClaimValues(String userName, Map<String, String> claims, String profileName)
            throws UserStoreException;

    /**
     * Set many user claim values
     *
     * @param user        The user object.
     * @param claims      Map of claim URIs against values
     * @param profileName The profile name, can be null. If null the default profile is considered.
     * @throws UserStoreException An unexpected exception has occurred
     */
    protected void doSetUserClaimValues(User user, Map<String, String> claims, String profileName)
            throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }

    /**
     * Delete a single user claim value
     *
     * @param userName    The user name
     * @param claimURI    Name of the claim
     * @param profileName The profile name, can be null. If null the default profile is considered.
     * @throws UserStoreException An unexpected exception has occurred
     */
    @Deprecated
    protected abstract void doDeleteUserClaimValue(String userName, String claimURI, String profileName)
            throws UserStoreException;

    /**
     * Delete a single user claim value
     *
     * @param user        The user object.
     * @param claimURI    Name of the claim
     * @param profileName The profile name, can be null. If null the default profile is considered.
     * @throws UserStoreException An unexpected exception has occurred
     */
    protected void doDeleteUserClaimValue(User user, String claimURI, String profileName)
            throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }

    /**
     * Delete many user claim values.
     *
     * @param userName    The user name
     * @param claims      URIs of the claims to be deleted.
     * @param profileName The profile name, can be null. If null the default profile is considered.
     * @throws UserStoreException An unexpected exception has occurred
     * @deprecated Please override the method doDeleteUserClaimValues(String userName, List<String> claims,
     *                                                                String profileName)
     */
    @Deprecated
    protected abstract void doDeleteUserClaimValues(String userName, String[] claims,
                                                    String profileName) throws UserStoreException;

    /**
     * Delete many user claim values.
     *
     * @param user        The user object.
     * @param claims      URIs of the claims to be deleted.
     * @param profileName The profile name, can be null. If null the default profile is considered.
     * @throws UserStoreException An unexpected exception has occurred
     */
    protected void doDeleteUserClaimValues(User user, List<String> claims, String profileName)
            throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }

    /**
     * Update user list of a particular role
     *
     * @param roleName     The role name
     * @param deletedUsers Array of user names, that is going to be removed from the role
     * @param newUsers     Array of user names, that is going to be added to the role
     * @throws UserStoreException An unexpected exception has occurred
     */
    protected abstract void doUpdateUserListOfRole(String roleName, String[] deletedUsers,
                                                   String[] newUsers) throws UserStoreException;

    /**
     * Update role list of a particular user
     *
     * @param userName     The user name
     * @param deletedRoles Array of role names, that is going to be removed from the user
     * @param newRoles     Array of role names, that is going to be added to the user
     * @throws UserStoreException An unexpected exception has occurred
     */
    @Deprecated
    protected abstract void doUpdateRoleListOfUser(String userName, String[] deletedRoles, String[] newRoles)
            throws UserStoreException;

    /**
     * Update role list of a particular user
     *
     * @param user         The user object.
     * @param deletedRoles Array of role names, that is going to be removed from the user
     * @param newRoles     Array of role names, that is going to be added to the user
     * @throws UserStoreException An unexpected exception has occurred
     */
    protected void doUpdateRoleListOfUser(User user, String[] deletedRoles, String[] newRoles)
            throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }

    /**
     * Only gets the internal roles of the user with internal domain name
     *
     * @param userName User object - who we need to find roles.
     * @return Array of roles.
     * @throws UserStoreException User store exception.
     */
    @Deprecated
    protected String[] doGetInternalRoleListOfUser(String userName, String filter) throws UserStoreException {

        User user = new UserImpl();
        user.setUserName(userName);
        return doGetInternalRoleListOfUser(user, filter);
    }

    /**
     * Only gets the internal roles of the user with internal domain name
     *
     * @param user User object - who we need to find roles.
     * @return Array of roles.
     * @throws UserStoreException User store exception.
     */
    protected String[] doGetInternalRoleListOfUser(User user, String filter) throws UserStoreException {

        if (Boolean.parseBoolean(realmConfig.getUserStoreProperty(MULIPLE_ATTRIBUTE_ENABLE))) {
            String userNameAttribute = realmConfig.getUserStoreProperty(LDAPConstants.USER_NAME_ATTRIBUTE);
            if (userNameAttribute != null && userNameAttribute.trim().length() > 0) {
                Map<String, String> map = getUserPropertyValues(user, new String[]{userNameAttribute}, null);
                String tempUserName = map.get(userNameAttribute);
                if (tempUserName != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Replaced user name for id : " + user.getId() + " from user property value : "
                                + tempUserName);
                    }
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Retrieving internal roles for user with id :  " + user.getId() + " and search filter " + filter);
        }
        return hybridRoleManager.getHybridRoleListOfUser(user, filter);
    }

    /**
     * Only gets the external roles of the user.
     *
     * @param userName Name of the user - who we need to find roles.
     * @return
     * @throws UserStoreException
     */
    @Deprecated
    protected abstract String[] doGetExternalRoleListOfUser(String userName, String filter)
            throws UserStoreException;

    /**
     * Only gets the external roles of the user.
     *
     * @param user User object of the user - who we need to find roles.
     * @return
     * @throws UserStoreException
     */
    protected String[] doGetExternalRoleListOfUser(User user, String filter)
            throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }

    /**
     * Returns the shared roles list of the user
     *
     * @param userName
     * @return
     * @throws UserStoreException
     */
    @Deprecated
    protected abstract String[] doGetSharedRoleListOfUser(String userName, String tenantDomain, String filter)
            throws UserStoreException;

    /**
     * Returns the shared roles list of the user
     *
     * @param user User object.
     * @return Array of user objects.
     * @throws UserStoreException
     */
    protected String[] doGetSharedRoleListOfUser(User user, String tenantDomain, String filter)
            throws UserStoreException {
        throw new UnsupportedOperationException("Method should be overridden in the child class.");
    }


    /**
     * Add role with a list of users and permissions provided.
     *
     * @param roleName
     * @param userList
     * @throws UserStoreException
     */
    protected abstract void doAddRole(String roleName, String[] userList, boolean shared) throws UserStoreException;


    /**
     * delete the role.
     *
     * @param roleName
     * @throws UserStoreException
     */
    protected abstract void doDeleteRole(String roleName) throws UserStoreException;

    /**
     * update the role name with the new name
     *
     * @param roleName
     * @param newRoleName
     * @throws UserStoreException
     */
    protected abstract void doUpdateRoleName(String roleName, String newRoleName)
            throws UserStoreException;

    /**
     * This method would returns the role Name actually this must be implemented in interface. As it
     * is not good to change the API in point release. This has been added to Abstract class
     *
     * @param filter
     * @param maxItemLimit
     * @return
     * @throws .UserStoreException
     */
    protected abstract String[] doGetRoleNames(String filter, int maxItemLimit)
            throws UserStoreException;

    /**
     * @param filter
     * @param maxItemLimit
     * @return
     * @throws UserStoreException
     */
    protected abstract String[] doListUsers(String filter, int maxItemLimit)
            throws UserStoreException;

    /**
     * This is to get the display names of users in hybrid role according to the underlying user store, to be shown in UI
     *
     *  @param userNames
     * @return
     * @throws UserStoreException
     */
    protected abstract String[] doGetDisplayNamesForInternalRole(String[] userNames)
            throws UserStoreException;

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean authenticate(final String userName, final Object credential) throws UserStoreException {
        try {
            return AccessController.doPrivileged((PrivilegedExceptionAction<Boolean>) () -> {
                if (userName == null || credential == null) {
                    log.error("Authentication failure. Either Username or Password is null");
                    return false;
                }
                int index = userName != null ? userName.indexOf(CarbonConstants.DOMAIN_SEPARATOR) : -1;
                boolean domainProvided = index > 0;
                return authenticate(userName, credential, domainProvided);
            });
        } catch (PrivilegedActionException e) {
            throw (UserStoreException) e.getException();
        }
    }

    @Override
    public final Authentication authenticate(User user, Object credential) throws UserStoreException {

        Authentication authentication = new Authentication();

        if (this.authenticate(user.getName(), credential)) {
            authentication.setSuccess(true);
            authentication.setUser(new PrivacyInsulator<>(user));
        }

        return authentication;
    }

    protected boolean authenticate(final String userName, final Object credential, final boolean domainProvided)
            throws UserStoreException {

        try {
            return AccessController.doPrivileged((PrivilegedExceptionAction<Boolean>) () ->
                    authenticateInternal(userName, credential, domainProvided));
        } catch (PrivilegedActionException e) {
            throw (UserStoreException) e.getException();
        }
    }

    /**
     * @param userName
     * @param credential
     * @param domainProvided
     * @return
     * @throws UserStoreException
     */
    private boolean authenticateInternal(String userName, Object credential, boolean domainProvided)
            throws UserStoreException {

        boolean authenticated;

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive() && userStore.getUserStoreManager() instanceof AbstractUserStoreManager) {
            return ((AbstractUserStoreManager) userStore.getUserStoreManager()).authenticate(userStore.getDomainFreeName(),
                    credential, domainProvided);
        }

        Secret credentialObj;
        try {
            credentialObj = Secret.getSecret(credential);
        } catch (UnsupportedSecretTypeException e) {
            throw new UserStoreException("Unsupported credential type", e);
        }

        // #################### Domain Name Free Zone Starts Here ################################

        // #################### <Listeners> #####################################################
        try {
            for (UserStoreManagerListener listener : UMListenerServiceComponent.getUserStoreManagerListeners()) {
                Object credentialArgument;
                if (listener instanceof SecretHandleableListener) {
                    credentialArgument = credentialObj;
                } else {
                    credentialArgument = credential;
                }

                if (!listener.authenticate(userName, credentialArgument, this)) {
                    return true;
                }
            }

            for (UserOperationEventListener listener : UMListenerServiceComponent.getUserOperationEventListeners()) {
                Object credentialArgument;
                if (listener instanceof SecretHandleableListener) {
                    credentialArgument = credentialObj;
                } else {
                    credentialArgument = credential;
                }

                if (!listener.doPreAuthenticate(userName, credentialArgument, this)) {
                    return false;
                }
            }
            // #################### </Listeners> #####################################################

            int tenantId = getTenantId();

            try {
                RealmService realmService = UserCoreUtil.getRealmService();
                if (realmService != null) {
                    boolean tenantActive = realmService.getTenantManager().isTenantActive(tenantId);

                    if (!tenantActive) {
                        log.warn("Tenant has been deactivated. TenantID : " + tenantId);
                        return false;
                    }
                }
            } catch (org.wso2.carbon.user.api.UserStoreException e) {
                throw new UserStoreException("Error while trying to check Tenant status for Tenant : "
                        + tenantId, e);
            }

            // We are here due to two reason. Either there is no secondary UserStoreManager or no
            // domain name provided with user name.

            try {
                // Let's authenticate with the primary UserStoreManager.
                authenticated = doAuthenticate(userName, credentialObj);
            } catch (Exception e) {
                // We can ignore and proceed. Ignore the results from this user store.
                log.error(e);
                authenticated = false;
            }

        } finally {
            credentialObj.clear();
        }

        if (authenticated) {
            // Set domain in thread local variable for subsequent operations
            String domain = UserCoreUtil.getDomainName(this.realmConfig);
            if (domain != null) {
                UserCoreUtil.setDomainInThreadLocal(domain.toUpperCase());
            }
        }

        // If authentication fails in the previous step and if the user has not specified a
        // domain- then we need to execute chained UserStoreManagers recursively.
        if (!authenticated && !domainProvided && this.getSecondaryUserStoreManager() != null) {
            authenticated = ((AbstractUserStoreManager) this.getSecondaryUserStoreManager())
                    .authenticate(userName, credential, domainProvided);
        }

        // You cannot change authentication decision in post handler to TRUE
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPostAuthenticate(userName, authenticated, this)) {
                return false;
            }
        }

        if (log.isDebugEnabled()) {
            if (!authenticated) {
                log.debug("Authentication failure. Wrong username or password is provided.");
            }
        }

        return authenticated;
    }

    @Deprecated
    public final String getUserClaimValue(String userName, String claim, String profileName) throws UserStoreException {

        User user = new UserImpl();
        user.setUserName(userName);
        return getUserClaimValue(user, claim, profileName);
    }

    /**
     * {@inheritDoc}
     */
    public final String getUserClaimValue(User user, String claim, String profileName)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{User.class, String.class, String.class};
            Object object = callSecure("getUserClaimValue", new Object[]{user, claim, profileName}, argTypes);
            return (String) object;
        }

        UserStore userStore = getUserStore(user.getId());
        if (userStore.isRecurssive()) {
            return userStore.getUserStoreManager().getUserClaimValue(userStore.getDomainFreeName(),
                    claim, profileName);
        }

        // #################### Domain Name Free Zone Starts Here ################################
        // If user does not exist, throw an exception

        if (!doCheckExistingUser(user)) {
            throw new UserStoreException(USER_NOT_FOUND + ": User with id " + user.getId() + "does not exist in: "
                    + realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME));
        }

        Map<String, String> finalValues = doGetUserClaimValues(user, new String[]{claim},
                userStore.getDomainName(), profileName);

        String value = null;

        if (finalValues != null) {
            value = finalValues.get(claim);
        }

        // #################### <Listeners> #####################################################

        List<String> list = new ArrayList<String>();
        if (value != null) {
            list.add(value);
        }

        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (listener instanceof AbstractUserOperationEventListener) {
                AbstractUserOperationEventListener newListener = (AbstractUserOperationEventListener) listener;
                if (!newListener.doPostGetUserClaimValue(user.getUsername(), claim, list, profileName, this)) {
                    break;
                }
            }
        }
        // #################### </Listeners> #####################################################

        if (!list.isEmpty()) {
            return list.get(0);
        }
        return value;
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    public final Claim[] getUserClaimValues(String userName, String profileName)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class};
            Object object = callSecure("getUserClaimValues", new Object[]{userName, profileName}, argTypes);
            return (Claim[]) object;
        }

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive()) {
            return userStore.getUserStoreManager().getUserClaimValues(
                    userStore.getDomainFreeName(), profileName);
        }

        // #################### Domain Name Free Zone Starts Here ################################
        // If user does not exist, throw exception
        if (!doCheckExistingUser(userName)) {
            throw new UserStoreException(USER_NOT_FOUND + ": User " + userName + "does not exist in: "
                    + realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME));
        }

        if (profileName == null || profileName.trim().length() == 0) {
            profileName = UserCoreConstants.DEFAULT_PROFILE;
        }

        String[] claims;
        try {
            claims = claimManager.getAllClaimUris();
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException(e);
        }

        Map<String, String> values = this.getUserClaimValues(userName, claims, profileName);
        Claim[] finalValues = new Claim[values.size()];
        int i = 0;
        for (Iterator<Map.Entry<String, String>> ite = values.entrySet().iterator(); ite.hasNext(); ) {
            Map.Entry<String, String> entry = ite.next();
            Claim claim = new Claim();
            claim.setValue(entry.getValue());
            claim.setClaimUri(entry.getKey());
            String displayTag;
            try {
                displayTag = claimManager.getClaim(entry.getKey()).getDisplayTag();
            } catch (org.wso2.carbon.user.api.UserStoreException e) {
                throw new UserStoreException(e);
            }
            claim.setDisplayTag(displayTag);
            finalValues[i] = claim;
            i++;
        }

        return finalValues;
    }

    @Deprecated
    public final Map<String, String> getUserClaimValues(String userName, String[] claims, String profileName)
            throws UserStoreException {

        User user = new UserImpl();
        user.setUserName(userName);

        return getUserClaimValues(user, claims, profileName);
    }

    /**
     * {@inheritDoc}
     */
    public final Map<String, String> getUserClaimValues(User user, String[] claims, String profileName)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{User.class, String[].class, String.class};
            Object object = callSecure("getUserClaimValues", new Object[]{user, claims, profileName}, argTypes);
            return (Map<String, String>) object;
        }

        UserStore userStore = getUserStore(user.getId());
        if (userStore.isRecurssive()) {
            return userStore.getUserStoreManager().getUserClaimValues(
                    userStore.getDomainFreeName(), claims, profileName);
        }

        // #################### Domain Name Free Zone Starts Here ################################
        if (!doCheckExistingUser(user)) {
            throw new UserStoreException(USER_NOT_FOUND + ": User for id " + user.getId() + "does not exist in: "
                    + realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME));
        }
        // check for null claim list
        if (claims == null) {
            claims = new String[0];
        }
        Map<String, String> finalValues = doGetUserClaimValues(user, claims, userStore.getDomainName(), profileName);

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (listener instanceof AbstractUserOperationEventListener) {
                AbstractUserOperationEventListener newListener = (AbstractUserOperationEventListener) listener;
                if (!newListener.doPostGetUserClaimValues(user.getUsername(), claims, profileName,
                        finalValues, this)) {
                    break;
                }
            }
        }
        // #################### </Listeners> #####################################################

        return finalValues;
    }

    /**
     * If the claim is domain qualified, search the users respective user store. Else we
     * return the users in all the user-stores recursively
     * {@inheritDoc}
     */
    public final String[] getUserList(String claim, String claimValue, String profileName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class};
            Object object = callSecure("getUserList", new Object[]{claim, claimValue, profileName}, argTypes);
            return (String[]) object;
        }

        if (claim == null) {
            throw new IllegalArgumentException("Claim URI cannot be null");
        }

        if (claimValue == null) {
            throw new IllegalArgumentException("Claim value cannot be null");
        }

        if (log.isDebugEnabled()) {
            log.debug("Listing users who having value as " + claimValue + " for the claim " + claim);
        }

        if (USERNAME_CLAIM_URI.equalsIgnoreCase(claim)) {

            if (log.isDebugEnabled()) {
                log.debug("Switching to list users using username");
            }

            String[] filteredUsers = listUsers(claimValue, MAX_ITEM_LIMIT_UNLIMITED);

            if (log.isDebugEnabled()) {
                log.debug("Filtered users: " + Arrays.toString(filteredUsers));
            }

            return filteredUsers;
        }

        // Extracting the domain from claimValue.
        String extractedDomain = null;
        int index;
        index = claimValue.indexOf(CarbonConstants.DOMAIN_SEPARATOR);
        if (index > 0) {
            String names[] = claimValue.split(CarbonConstants.DOMAIN_SEPARATOR);
            extractedDomain = names[0].trim();
        }

        UserStoreManager userManager = null;
        if (StringUtils.isNotEmpty(extractedDomain)) {
            userManager = getSecondaryUserStoreManager(extractedDomain);
            if (log.isDebugEnabled()) {
                log.debug("Domain: " + extractedDomain + " is passed with the claim and user store manager is loaded" +
                        " for the given domain name.");
            }
        }

        if (userManager instanceof JDBCUserStoreManager && SCIM_USERNAME_CLAIM_URI.equalsIgnoreCase(claim)) {
            if (userManager.isExistingUser(claimValue)) {
                return new String[] {claimValue};
            } else {
                return new String [0];
            }
        }

        claimValue = UserCoreUtil.removeDomainFromName(claimValue);

        final List<String> filteredUserList = new ArrayList<>();

        if (StringUtils.isNotEmpty(extractedDomain)) {
            for (UserOperationEventListener listener : UMListenerServiceComponent.getUserOperationEventListeners()) {
                if (listener instanceof AbstractUserOperationEventListener) {
                    AbstractUserOperationEventListener newListener = (AbstractUserOperationEventListener) listener;
                    if (!newListener.doPreGetUserList(claim, claimValue, filteredUserList, userManager)) {
                        break;
                    }
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Pre listener user list: " + filteredUserList + " for domain: " + extractedDomain);
        }

        // Iterate through user stores and check for users for this claim.
        List<String> usersFromUserStore = doGetUserList(claim, claimValue, profileName, extractedDomain, userManager);
        if (log.isDebugEnabled()) {
            log.debug("Users from user store: " + extractedDomain + " : " + usersFromUserStore);
        }
        filteredUserList.addAll(usersFromUserStore);

        if (StringUtils.isNotEmpty(extractedDomain)) {
            for (UserOperationEventListener listener : UMListenerServiceComponent.getUserOperationEventListeners()) {
                if (listener instanceof AbstractUserOperationEventListener) {
                    AbstractUserOperationEventListener newListener = (AbstractUserOperationEventListener) listener;
                    if (!newListener.doPostGetUserList(claim, claimValue, filteredUserList, userManager)) {
                        break;
                    }
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Post listener user list: " + filteredUserList + " for domain: " + extractedDomain);
        }

        Collections.sort(filteredUserList);
        return filteredUserList.toArray(new String[0]);
    }

    private List<String> doGetUserList(String claim, String claimValue, String profileName, String extractedDomain,
                                       UserStoreManager userManager)
        throws UserStoreException {

        String property;

        // If domain is present, then we search within that domain only.
        if (StringUtils.isNotEmpty(extractedDomain)) {

            if (userManager == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No user store manager found for domain: " + extractedDomain);
                }
                return Collections.emptyList();
            }

            if (log.isDebugEnabled()) {
                log.debug("Domain found in claim value. Searching only in the " + extractedDomain + " for possible " +
                        "matches");
            }

            try {
                property = claimManager.getAttributeName(extractedDomain, claim);
            } catch (org.wso2.carbon.user.api.UserStoreException e) {
                throw new UserStoreException("Error occurred while retrieving attribute name for domain : " +
                        extractedDomain + " and claim " + claim);
            }
            if (property == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Could not find matching property for\n" +
                            "claim :" + claim +
                            "domain :" + extractedDomain);
                }
                return Collections.emptyList();
            }

            if (userManager instanceof AbstractUserStoreManager) {

                // Get the user list and return with domain appended.
                AbstractUserStoreManager userStoreManager = (AbstractUserStoreManager) userManager;
                String[] userArray = userStoreManager.getUserListFromProperties(property, claimValue, profileName);
                if (log.isDebugEnabled()) {
                    log.debug("List of filtered users for: " + extractedDomain + " : " + Arrays.asList(userArray));
                }
                return Arrays.asList(UserCoreUtil.addDomainToNames(userArray, extractedDomain));
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("getUserListFromProperties is not supported by this user store: "
                            + userManager.getClass());
                }
                return Collections.emptyList();
            }
        }

        // If domain is not given then search all the user stores.
        if (log.isDebugEnabled()) {
            log.debug("No domain name found in claim value. Searching through all user stores for possible matches");
        }

        List<String> usersFromAllStoresList = new ArrayList<>();
        List<UserStoreManager> userStoreManagers = getUserStoreMangers();

        // Iterate through all of available user store managers.
        for (UserStoreManager userStoreManager : userStoreManagers) {

            // If this is not an instance of Abstract User Store Manger we can ignore the flow since we can't get the
            // domain name.
            if (!(userStoreManager instanceof AbstractUserStoreManager)) {
                continue;
            }

            // For all the user stores append the domain name to the claim and pass it recursively (Including PRIMARY).
            String domainName = ((AbstractUserStoreManager) userStoreManager).getMyDomainName();
            String claimValueWithDomain;
            if (StringUtils.equalsIgnoreCase(domainName, UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME)) {
                claimValueWithDomain = domainName + CarbonConstants.DOMAIN_SEPARATOR + claimValue;
            } else {
                claimValueWithDomain = UserCoreUtil.addDomainToName(claimValue, domainName);
            }

            if (log.isDebugEnabled()) {
                log.debug("Invoking the get user list for domain: " + domainName + " for claim: " + claim +
                        " value: " + claimValueWithDomain);
            }

            // Recursively call the getUserList method appending the domain to claim value.
            List<String> userList = Arrays.asList(getUserList(claim, claimValueWithDomain, profileName));
            if (log.isDebugEnabled()) {
                log.debug("Secondary user list for domain: " + domainName + " : " + userList);
            }

            usersFromAllStoresList.addAll(userList);
        }

        // Done with all user store processing. Return the user array if not empty.
        return usersFromAllStoresList;
    }

    /**
     * Get the list of user store managers available including primary user store manger.
     * @return List of user store managers available.
     */
    private List<UserStoreManager> getUserStoreMangers() {

        List<UserStoreManager> userStoreManagers = new ArrayList<>();
        UserStoreManager currentUserStoreManager = this;

        // Get the list of user store managers(Including PRIMARY). Later we have to iterate through them.
        while (currentUserStoreManager != null) {
            userStoreManagers.add(currentUserStoreManager);
            currentUserStoreManager = currentUserStoreManager.getSecondaryUserStoreManager();
        }

        return userStoreManagers;
    }

    /**
     * {@inheritDoc}
     */
    public final void updateCredential(String userName, Object newCredential, Object oldCredential)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, Object.class, Object.class};
            callSecure("updateCredential", new Object[]{userName, newCredential, oldCredential}, argTypes);
            return;
        }

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().updateCredential(userStore.getDomainFreeName(),
                    newCredential, oldCredential);
            return;
        }

        // #################### Domain Name Free Zone Starts Here ################################

        if (isReadOnly()) {
            throw new UserStoreException(INVALID_OPERATION + " Invalid operation. User store is read only");
        }

        Secret newCredentialObj;
        Secret oldCredentialObj;
        try {
            newCredentialObj = Secret.getSecret(newCredential);
            oldCredentialObj = Secret.getSecret(oldCredential);
        } catch (UnsupportedSecretTypeException e) {
            throw new UserStoreException("Unsupported credential type");
        }

        // #################### <Listeners> #####################################################
        try {
            for (UserStoreManagerListener listener : UMListenerServiceComponent.getUserStoreManagerListeners()) {
                if (listener instanceof SecretHandleableListener) {
                    if (!listener.updateCredential(userName, newCredentialObj, oldCredentialObj, this)) {
                        return;
                    }
                } else {
                    if (!listener.updateCredential(userName, newCredential, oldCredential, this)) {
                        return;
                    }
                }
            }

            for (UserOperationEventListener listener : UMListenerServiceComponent.getUserOperationEventListeners()) {
                if (listener instanceof SecretHandleableListener) {
                    if (!listener.doPreUpdateCredential(userName, newCredentialObj, oldCredentialObj, this)) {
                        return;
                    }
                } else {
                    if (!listener.doPreUpdateCredential(userName, newCredential, oldCredential, this)) {
                        return;
                    }
                }
            }
            // #################### </Listeners> #####################################################

            // This user name here is domain-less.
            // We directly authenticate user against the selected UserStoreManager.
            boolean isAuth = this.doAuthenticate(userName, oldCredentialObj);

            if (isAuth) {
                if (!checkUserPasswordValid(newCredential)) {
                    String errorMsg = realmConfig
                            .getUserStoreProperty(PROPERTY_PASSWORD_ERROR_MSG);

                    if (errorMsg != null) {
                        throw new UserStoreException(errorMsg);
                    }

                    throw new UserStoreException(
                            "Credential not valid. Credential must be a non null string with following format, "
                                    + realmConfig
                                    .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_JAVA_REG_EX));

                }

                this.doUpdateCredential(userName, newCredential, oldCredential);

                // #################### <Listeners> ##################################################
                for (UserOperationEventListener listener : UMListenerServiceComponent
                        .getUserOperationEventListeners()) {
                    if (listener instanceof SecretHandleableListener) {
                        if (!listener.doPostUpdateCredential(userName, newCredentialObj, this)) {
                            return;
                        }
                    } else {
                        if (!listener.doPostUpdateCredential(userName, newCredential, this)) {
                            return;
                        }
                    }
                }
                // #################### </Listeners> ##################################################

                return;
            } else {
                throw new UserStoreException(
                        INVALID_PASSWORD + " Old credential does not match with the existing credentials.");
            }
        } finally {
            newCredentialObj.clear();
            oldCredentialObj.clear();
        }
    }

    /**
     * {@inheritDoc}
     */
    public final void updateCredentialByAdmin(String userName, Object newCredential)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, Object.class};
            callSecure("updateCredentialByAdmin", new Object[]{userName, newCredential}, argTypes);
            return;
        }

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().updateCredentialByAdmin(userStore.getDomainFreeName(),
                    newCredential);
            return;
        }

        // #################### Domain Name Free Zone Starts Here ################################

        if (isReadOnly()) {
            throw new UserStoreException(INVALID_OPERATION + "Invalid operation. User store is read only");
        }

        Secret newCredentialObj;
        try {
            newCredentialObj = Secret.getSecret(newCredential);
        } catch (UnsupportedSecretTypeException e) {
            throw new UserStoreException("Unsupported credential type", e);
        }

        try {
            // #################### <Listeners> #####################################################
            for (UserStoreManagerListener listener : UMListenerServiceComponent.getUserStoreManagerListeners()) {
                Object credentialArgument;
                if (listener instanceof SecretHandleableListener) {
                    credentialArgument = newCredentialObj;
                } else {
                    credentialArgument = newCredential;
                }

                if (!listener.updateCredentialByAdmin(userName, credentialArgument, this)) {
                    return;
                }
            }

            // using string buffers to allow the password to be changed by listener
            for (UserOperationEventListener listener : UMListenerServiceComponent.getUserOperationEventListeners()) {

                if (listener instanceof SecretHandleableListener) {
                    if (!listener.doPreUpdateCredentialByAdmin(userName, newCredentialObj, this)) {
                        return;
                    }
                } else {
                    // using string buffers to allow the password to be changed by listener
                    StringBuffer credBuff = null;
                    if (newCredential == null) { // a default password will be set
                        credBuff = new StringBuffer();
                    } else if (newCredential instanceof String) {
                        credBuff = new StringBuffer((String) newCredential);
                    }

                    if (credBuff != null) {
                        if (!listener.doPreUpdateCredentialByAdmin(userName, credBuff, this)) {
                            return;
                        }
                        // reading the modified value
                        newCredential = credBuff.toString();
                        newCredentialObj.clear();
                        try {
                            newCredentialObj = Secret.getSecret(newCredential);
                        } catch (UnsupportedSecretTypeException e) {
                            throw new UserStoreException("Unsupported credential type", e);
                        }
                    }
                }
            }
            // #################### </Listeners> #####################################################

            if (!checkUserPasswordValid(newCredential)) {
                String errorMsg = realmConfig
                        .getUserStoreProperty(PROPERTY_PASSWORD_ERROR_MSG);

                if (errorMsg != null) {
                    throw new UserStoreException(errorMsg);
                }

                throw new UserStoreException(
                        "Credential not valid. Credential must be a non null string with following format, "
                                + realmConfig
                                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_JAVA_REG_EX));

            }

            if (!doCheckExistingUser(userStore.getDomainFreeName())) {
                throw new UserStoreException("User " + userName + " does not exisit in the user store");
            }

            doUpdateCredentialByAdmin(userName, newCredentialObj);

            // #################### <Listeners> #####################################################
            for (UserOperationEventListener listener : UMListenerServiceComponent.getUserOperationEventListeners()) {
                Object credentialArgument;
                if (listener instanceof SecretHandleableListener) {
                    credentialArgument = newCredentialObj;
                } else {
                    credentialArgument = newCredential;
                }

                if (!listener.doPostUpdateCredentialByAdmin(userName, credentialArgument, this)) {
                    return;
                }
            }
        } finally {
            newCredentialObj.clear();
        }
        // #################### </Listeners> #####################################################

    }

    /**
     * Get the attribute for the provided claim uri and identifier.
     *
     * @param claimURI
     * @param identifier user name or role.
     * @param domainName TODO
     * @return claim attribute value. NULL if attribute is not defined for the
     * claim uri
     * @throws org.wso2.carbon.user.api.UserStoreException
     */
    protected String getClaimAtrribute(String claimURI, String identifier, String domainName)
            throws org.wso2.carbon.user.api.UserStoreException {

        domainName =
                (domainName == null || domainName.isEmpty())
                        ? (identifier.contains(UserCoreConstants.DOMAIN_SEPARATOR)
                        ? identifier.split(UserCoreConstants.DOMAIN_SEPARATOR)[0]
                        : realmConfig.getUserStoreProperty(UserStoreConfigConstants.DOMAIN_NAME))
                        : domainName;
        String attributeName = null;
        if (domainName != null && !domainName.equals(UserStoreConfigConstants.PRIMARY)) {
            attributeName = claimManager.getAttributeName(domainName, claimURI);
        }
        if (attributeName == null || attributeName.isEmpty()) {
            attributeName = claimManager.getAttributeName(claimURI);
        }

        if (attributeName == null) {
            if (UserCoreConstants.PROFILE_CONFIGURATION.equals(claimURI)) {
                attributeName = claimURI;
            } else if (DISAPLAY_NAME_CLAIM.equals(claimURI)) {
                attributeName = this.realmConfig.getUserStoreProperty(LDAPConstants.DISPLAY_NAME_ATTRIBUTE);
            } else {
                throw new UserStoreException("Mapped attribute cannot be found for claim : " + claimURI + " in user " +
                        "store : " + getMyDomainName());
            }
        }

        return attributeName;
    }

    /**
     * {@inheritDoc}
     */
    public final void deleteUser(String userName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class};
            callSecure("deleteUser", new Object[]{userName}, argTypes);
            return;
        }

        String loggedInUser = CarbonContext.getThreadLocalCarbonContext().getUsername();
        if (loggedInUser != null) {
            loggedInUser = UserCoreUtil.addDomainToName(loggedInUser, UserCoreUtil.getDomainFromThreadLocal());
            if ((loggedInUser.indexOf(UserCoreConstants.DOMAIN_SEPARATOR)) < 0) {
                loggedInUser = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME +
                        CarbonConstants.DOMAIN_SEPARATOR + loggedInUser;
            }
        }

        String deletingUser = UserCoreUtil.addDomainToName(userName, getMyDomainName());
        if ((deletingUser.indexOf(UserCoreConstants.DOMAIN_SEPARATOR)) < 0) {
            deletingUser = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME +
                    CarbonConstants.DOMAIN_SEPARATOR + deletingUser;
        }

        if (loggedInUser != null && loggedInUser.equals(deletingUser)) {
            log.debug("User " + loggedInUser + " tried to delete him/her self");
            throw new UserStoreException(LOGGED_IN_USER + " Cannot delete logged in user");
        }

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().deleteUser(userStore.getDomainFreeName());
            return;
        }

        // #################### Domain Name Free Zone Starts Here ################################

        if (UserCoreUtil.isPrimaryAdminUser(userName, realmConfig)) {
            throw new UserStoreException(ADMIN_USER + "Cannot delete admin user");
        }

        if (UserCoreUtil.isRegistryAnnonymousUser(userName)) {
            throw new UserStoreException(ANONYMOUS_USER + "Cannot delete anonymous user");
        }

        if (isReadOnly()) {
            throw new UserStoreException(INVALID_OPERATION + " Invalid operation. User store is read only");
        }

        // #################### <Listeners> #####################################################
        for (UserStoreManagerListener listener : UMListenerServiceComponent
                .getUserStoreManagerListeners()) {
            if (!listener.deleteUser(userName, this)) {
                return;
            }
        }

        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPreDeleteUser(userName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

        if (!doCheckExistingUser(userName)) {
            throw new UserStoreException("Cannot delete user who is not exist");
        }

        // Remove users from internal role mapping
        hybridRoleManager.deleteUser(UserCoreUtil.addDomainToName(userName, getMyDomainName()));

        doDeleteUser(userName);

        // Needs to clear roles cache upon deletion of a user
        clearUserRolesCache(UserCoreUtil.addDomainToName(userName, getMyDomainName()));

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPostDeleteUser(userName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

    }

    /**
     * {@inheritDoc}
     */
    public final void setUserClaimValue(String userName, String claimURI, String claimValue,
                                        String profileName) throws UserStoreException {

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().setUserClaimValue(userStore.getDomainFreeName(),
                    claimURI, claimValue, profileName);
            return;
        }

        // #################### Domain Name Free Zone Starts Here ################################

        if (!doCheckExistingUser(userName)) {
            throw new UserStoreException(USER_NOT_FOUND + ": User " + userName + "does not exist in: "
                    + realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME));
        }

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPreSetUserClaimValue(userName, claimURI, claimValue, profileName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

        //Check userstore is readonly or not

        if (isReadOnly()) {
            throw new UserStoreException(INVALID_OPERATION + " Invalid operation. User store is read only");
        }


        doSetUserClaimValue(userName, claimURI, claimValue, profileName);

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPostSetUserClaimValue(userName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

    }

    /**
     * {@inheritDoc}
     */
    public final void setUserClaimValues(String userName, Map<String, String> claims,
                                         String profileName) throws UserStoreException {

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().setUserClaimValues(userStore.getDomainFreeName(),
                    claims, profileName);
            return;
        }

        // #################### Domain Name Free Zone Starts Here ################################

        if (!doCheckExistingUser(userName)) {
            throw new UserStoreException(USER_NOT_FOUND + ": User " + userName + "does not exist in: "
                    + realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME));
        }
        if (claims == null) {
            claims = new HashMap<>();
        }
        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPreSetUserClaimValues(userName, claims, profileName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

        //If user store is readonly this method should not get invoked with non empty claim set.

        if (isReadOnly() && !claims.isEmpty()) {
            throw new UserStoreException(INVALID_OPERATION + " Invalid operation. User store is read only");
        }

        // set claim values if user store is not read only.

        if (!isReadOnly()) {
            doSetUserClaimValues(userName, claims, profileName);
        }

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPostSetUserClaimValues(userName, claims, profileName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

    }

    /**
     * {@inheritDoc}
     */
    public final void deleteUserClaimValue(String userName, String claimURI, String profileName)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class};
            callSecure("deleteUserClaimValue", new Object[]{userName, claimURI, profileName}, argTypes);
            return;
        }

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().deleteUserClaimValue(userStore.getDomainFreeName(),
                    claimURI, profileName);
            return;
        }

        if (isReadOnly()) {
            throw new UserStoreException(INVALID_OPERATION + " Invalid operation. User store is read only");
        }

        if (!doCheckExistingUser(userName)) {
            throw new UserStoreException(USER_NOT_FOUND + ": User " + userName + "does not exist in: "
                    + realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME));
        }

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPreDeleteUserClaimValue(userName, claimURI, profileName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################


        doDeleteUserClaimValue(userName, claimURI, profileName);

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPostDeleteUserClaimValue(userName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################
    }

    /**
     * {@inheritDoc}
     */
    public final void deleteUserClaimValues(User user, List<String> claims, String profileName)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{User.class, String[].class, String.class};
            callSecure("deleteUserClaimValues", new Object[]{user, claims, profileName}, argTypes);
            return;
        }

        UserStore userStore = getUserStore(user.getId());
        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().deleteUserClaimValues(user, claims, profileName);
            return;
        }

        if (isReadOnly()) {
            throw new UserStoreException(INVALID_OPERATION + " Invalid operation. User store is read only");
        }

        if (!doCheckExistingUser(user)) {
            throw new UserStoreException(USER_NOT_FOUND + ": User for id " + user.getId() + "does not exist in: "
                    + realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME));
        }

        if (claims == null) {
            claims = new ArrayList<>();
        }
        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (StringUtils.isEmpty(user.getId())) {
                if (!listener.doPreDeleteUserClaimValues(user, claims, profileName, this)) {
                    return;
                }
            } else {
                if (!listener.doPreDeleteUserClaimValues(user, claims, profileName, this)) {
                    return;
                }
            }
        }
        // #################### </Listeners> #####################################################


        doDeleteUserClaimValues(user, claims, profileName);

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (StringUtils.isEmpty(user.getId())) {
                if (!listener.doPostDeleteUserClaimValues(user.getName(), this)) {
                    return;
                }
            } else {
                if (!listener.doPostDeleteUserClaimValues(user, this)) {
                    return;
                }
            }
        }
        // #################### </Listeners> #####################################################

    }

    @Deprecated
    public final void deleteUserClaimValues(String userName, String[] claims, String profileName)
            throws UserStoreException {

        User user = new UserImpl();
        user.setUserName(userName);
        deleteUserClaimValues(user, Arrays.asList(claims), profileName);
    }

    @Override
    public void addUser(User user, Object credential, List<String> roleList, Map<String, String> claims,
                        String profileName, boolean requirePasswordChange) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{User.class, Object.class, String[].class, Map.class, String.class,
                    boolean.class};
            callSecure("addUser", new Object[]{user, credential, roleList, claims, profileName,
                    requirePasswordChange}, argTypes);
            return;
        }

        if (StringUtils.isEmpty(user.getId())) {
            try {
                user = (User) idManager.addIdForName(user);
            } catch (IdManagerException e) {
                throw new UserStoreException(e);
            }
        }

        String userId = user.getId();
        String username = user.getName();

        UserStore userStore = getUserStore(userId);
        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().addUser(user, credential, roleList, claims, profileName,
                    requirePasswordChange);
            return;
        }

        Secret credentialObj;
        try {
            credentialObj = Secret.getSecret(credential);
        } catch (UnsupportedSecretTypeException e) {
            throw new UserStoreException("Unsupported credential type", e);
        }

        try {
            if (userStore.isSystemStore()) {
                systemUserRoleManager.addSystemUser(username, credentialObj, roleList);
                return;
            }

            // #################### Domain Name Free Zone Starts Here ################################

            if (isReadOnly()) {
                throw new UserStoreException(INVALID_OPERATION + " Invalid operation. User store is read only");
            }
            // This happens only once during first startup - adding administrator user/role.
            if (userId.indexOf(CarbonConstants.DOMAIN_SEPARATOR) > 0) {
                userId = userStore.getDomainFreeName();
                roleList = UserCoreUtil.removeDomainFromNames(roleList);
            }
            if (roleList == null) {
                roleList = new ArrayList<>();
            }
            if (claims == null) {
                claims = new HashMap<>();
            }

            // #################### <Pre-Listeners> #####################################################
            if (!callPreAddUserStoreMangerListener(credential, credentialObj, null, user, roleList, claims,
                    profileName)) {
                return;
            }

            if (!callPreAddUserOperationEventLatiner(credential, credentialObj, null, user, roleList, claims,
                    profileName)) {
                return;
            }
            // #################### </Pre-Listeners> #####################################################

            if (!checkUserNameValid(userStore.getDomainFreeName())) {
                String message = "Username for " + userId + " is not valid. User name must be a non null string with " +
                        "following format, ";
                String regEx = realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig
                        .PROPERTY_USER_NAME_JAVA_REG_EX);
                throw new UserStoreException(message + regEx);
            }

            if (!checkUserPasswordValid(credentialObj)) {
                String message = "Credential not valid. Credential must be a non null string with following format, ";
                String regEx = realmConfig.getUserStoreProperty(UserCoreConstants
                        .RealmConfig.PROPERTY_JAVA_REG_EX);
                throw new UserStoreException(message + regEx);
            }

            if (doCheckExistingUser(user)) {
                throw new UserStoreException(EXISTING_USER + "Username for '" + user.getId() + "' already exists in " +
                        "the system. Please pick another username.");
            }

            List<String> internalRoles = new ArrayList<>();
            List<String> externalRoles = new ArrayList<>();

            // Filter roles for internal and external.
            filterRolesForExternalAndInternal(roleList, internalRoles, externalRoles);

            // check existence of roles and claims before user is adding
            for (String internalRole : internalRoles) {
                if (!hybridRoleManager.isExistingRole(internalRole)) {
                    throw new UserStoreException("Internal role is not exist : " + internalRole);
                }
            }

            for (String externalRole : externalRoles) {
                if (!doCheckExistingRole(externalRole)) {
                    throw new UserStoreException("External role is not exist : " + externalRole);
                }
            }

            // Validate the claim mappings.
            validateClaimMapping(claims);

            if (idManager == null) {
                throw new UserStoreException("Id manager instance is null.");
            }

            doAddUser(user, credentialObj, externalRoles.toArray(new String[externalRoles.size()]),
                    claims, profileName, requirePasswordChange);

            if (internalRoles.size() > 0) {
                hybridRoleManager.updateHybridRoleListOfUser(user, null, internalRoles
                        .toArray(new String[internalRoles.size()]));
            }

            // #################### <Post-Listeners> #####################################################
            if (!callPostAddUserListener(credential, credentialObj, null, user, roleList, claims, profileName)) {
                return;
            }
            // #################### </Post-Listeners> #####################################################

        } finally {
            credentialObj.clear();
        }

        // Clean the role cache since it contains old role information
        clearUserRolesCache(user.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    public final void addUser(String userName, Object credential, String[] roleList,
                              Map<String, String> claims, String profileName, boolean requirePasswordChange)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, Object.class, String[].class, Map.class, String.class,
                    boolean.class};
            callSecure("addUser", new Object[]{userName, credential, roleList, claims, profileName,
                    requirePasswordChange}, argTypes);
            return;
        }

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().addUser(userStore.getDomainFreeName(), credential,
                    roleList, claims, profileName, requirePasswordChange);
            return;
        }

        Secret credentialObj;
        try {
            credentialObj = Secret.getSecret(credential);
        } catch (UnsupportedSecretTypeException e) {
            throw new UserStoreException("Unsupported credential type", e);
        }

        try {
            if (userStore.isSystemStore()) {
                systemUserRoleManager.addSystemUser(userName, credentialObj, roleList);
                return;
            }

            // #################### Domain Name Free Zone Starts Here ################################

            if (isReadOnly()) {
                throw new UserStoreException(INVALID_OPERATION + " Invalid operation. User store is read only");
            }
            // This happens only once during first startup - adding administrator user/role.
            if (userName.indexOf(CarbonConstants.DOMAIN_SEPARATOR) > 0) {
                userName = userStore.getDomainFreeName();
                roleList = UserCoreUtil.removeDomainFromNames(roleList);
            }
            if (roleList == null) {
                roleList = new String[0];
            }
            if (claims == null) {
                claims = new HashMap<>();
            }

            // #################### <Pre-Listeners> #####################################################

            if (!callPreAddUserStoreMangerListener(credential, credentialObj, userName, null, Arrays.asList(roleList),
                    claims, profileName)) {
                return;
            }

            if (!callPreAddUserOperationEventLatiner(credential, credentialObj, userName, null, Arrays.asList(roleList),
                    claims, profileName)) {
                return;
            }

            // #################### </Per-Listeners> #####################################################

            if (!checkUserNameValid(userStore.getDomainFreeName())) {
                String message = "Username " + userStore.getDomainFreeName() + " is not valid. User name must be a " +
                        "non null string with following format, ";
                String regEx = realmConfig
                        .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_USER_NAME_JAVA_REG_EX);
                throw new UserStoreException(message + regEx);
            }

            if (!checkUserPasswordValid(credentialObj)) {
                String message = "Credential not valid. Credential must be a non null string with following format, ";
                String regEx = realmConfig
                        .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_JAVA_REG_EX);
                throw new UserStoreException(message + regEx);
            }

            if (doCheckExistingUser(userStore.getDomainFreeName())) {
                throw new UserStoreException(EXISTING_USER + "Username '" + userName
                        + "' already exists in the system. Please pick another username.");
            }

            List<String> internalRoles = new ArrayList<>();
            List<String> externalRoles = new ArrayList<>();

            // Filter roles for internal and external.
            filterRolesForExternalAndInternal(Arrays.asList(roleList), internalRoles, externalRoles);

            // check existence of roles and claims before user is adding
            for (String internalRole : internalRoles) {
                if (!hybridRoleManager.isExistingRole(internalRole)) {
                    throw new UserStoreException("Internal role is not exist : " + internalRole);
                }
            }

            for (String externalRole : externalRoles) {
                if (!doCheckExistingRole(externalRole)) {
                    throw new UserStoreException("External role is not exist : " + externalRole);
                }
            }

            validateClaimMapping(claims);

            if (idManager == null) {
                throw new UserStoreException("Id manager instance is null.");
            }

            doAddUser(userName, credentialObj, externalRoles.toArray(new String[externalRoles.size()]),
                    claims, profileName, requirePasswordChange);

            if (internalRoles.size() > 0) {
                hybridRoleManager.updateHybridRoleListOfUser(userName, null,
                        internalRoles.toArray(new String[internalRoles.size()]));
            }

            // #################### <Post-Listeners> #####################################################
            if (!callPostAddUserListener(credential, credentialObj, userName, null, Arrays.asList(roleList), claims,
                    profileName)) {
                return;
            }
            // #################### </Post-Listeners> #####################################################

        } finally {
            credentialObj.clear();
        }

        // Clean the role cache since it contains old role informations
        clearUserRolesCache(userName);
    }

    private boolean callPreAddUserStoreMangerListener(Object credential, Secret credentialObj, String username,
                                                      User user, List<String> roleList, Map<String, String> claims,
                                                      String profileName) throws UserStoreException {

        for (UserStoreManagerListener listener : UMListenerServiceComponent.getUserStoreManagerListeners()) {
            Object credentialArgument;
            if (listener instanceof SecretHandleableListener) {
                credentialArgument = credentialObj;
            } else {
                credentialArgument = credential;
            }

            if (StringUtils.isEmpty(username)) {
                if (!listener.addUser(user, credentialArgument, roleList, claims, profileName, this)) {
                    return false;
                }
            } else {
                if (!listener.addUser(username, credentialArgument, roleList.toArray(new String[roleList.size()]),
                        claims, profileName, this)) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean callPreAddUserOperationEventLatiner(Object credential, Secret credentialObj, String username,
                                                        User user, List<String> roleList, Map<String, String> claims,
                                                        String profileName) throws UserStoreException {

        // String buffers are used to let listeners to modify passwords
        for (UserOperationEventListener listener : UMListenerServiceComponent.getUserOperationEventListeners()) {
            if (listener instanceof SecretHandleableListener) {
                callDoPreAddUserListener(listener, credentialObj, username, user, roleList, claims, profileName);
            } else {
                // String buffers are used to let listeners to modify passwords
                StringBuffer credBuff = null;
                if (credential == null) { // a default password will be set
                    credBuff = new StringBuffer();
                } else if (credential instanceof String) {
                    credBuff = new StringBuffer((String) credential);
                }

                if (credBuff != null) {
                    callDoPreAddUserListener(listener, credBuff, username, user, roleList, claims, profileName);
                    // reading the modified value
                    credential = credBuff.toString();
                    credentialObj.clear();
                    try {
                        credentialObj = Secret.getSecret(credential);
                    } catch (UnsupportedSecretTypeException e) {
                        throw new UserStoreException("Unsupported credential type", e);
                    }
                }
            }
        }

        return true;
    }

    private boolean callDoPreAddUserListener(UserOperationEventListener listener, Object credentialObj, String username,
                                             User user, List<String> roleList, Map<String, String> claims,
                                             String profileName) throws UserStoreException {

        if (StringUtils.isEmpty(username)) {
            if (!listener.doPreAddUser(user, credentialObj, roleList, claims, profileName, this)) {
                return false;
            }
        } else {
            if (!listener.doPreAddUser(username, credentialObj, roleList.toArray(new String[roleList.size()]),
                    claims, profileName, this)) {
                return false;
            }
        }
        return true;
    }

    private boolean callPostAddUserListener(Object credential, Secret credentialObj, String username,
                                            User user, List<String> roleList, Map<String, String> claims,
                                            String profileName) throws UserStoreException {

        for (UserOperationEventListener listener : UMListenerServiceComponent.getUserOperationEventListeners()) {
            Object credentialArgument;
            if (listener instanceof SecretHandleableListener) {
                credentialArgument = credentialObj;
            } else {
                credentialArgument = credential;
            }

            if (StringUtils.isEmpty(username)) {
                if (!listener.doPostAddUser(username, credentialArgument, roleList.toArray(new String[roleList.size()]),
                        claims, profileName, this)) {
                    return false;
                }
            } else {
                if (!listener.doPostAddUser(user, credentialArgument, roleList, claims, profileName, this)) {
                    return false;
                }
            }
        }

        return true;
    }

    private void filterRolesForExternalAndInternal(List<String> roleList, List<String> internalRoles, List<String>
            externalRoles) {

        int index;
        for (String role : roleList) {
            if (role != null && role.trim().length() > 0) {
                index = role.indexOf(CarbonConstants.DOMAIN_SEPARATOR);
                if (index > 0) {
                    String domain = role.substring(0, index);
                    if (UserCoreConstants.INTERNAL_DOMAIN.equalsIgnoreCase(domain)) {
                        internalRoles.add(UserCoreUtil.removeDomainFromName(role));
                        continue;
                    } else if (APPLICATION_DOMAIN.equalsIgnoreCase(domain) ||
                            WORKFLOW_DOMAIN.equalsIgnoreCase(domain)) {
                        internalRoles.add(role);
                        continue;
                    }
                }
                externalRoles.add(UserCoreUtil.removeDomainFromName(role));
            }
        }
    }

    private void validateClaimMapping(Map<String, String> claims) throws UserStoreException {

        for (Map.Entry<String, String> entry : claims.entrySet()) {
            ClaimMapping claimMapping;
            try {
                claimMapping = (ClaimMapping) claimManager.getClaimMapping(entry.getKey());
            } catch (org.wso2.carbon.user.api.UserStoreException e) {
                String errorMessage = "Error in obtaining claim mapping for persisting user attributes.";
                throw new UserStoreException(errorMessage, e);
            }
            if (claimMapping == null) {
                String errorMessage = "Invalid claim uri has been provided: " + entry.getKey();
                throw new UserStoreException(errorMessage);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void addUser(String userName, Object credential, String[] roleList,
                        Map<String, String> claims, String profileName) throws UserStoreException {
        this.addUser(userName, credential, roleList, claims, profileName, false);
    }

    public final void updateUserListOfRole(final String roleName, final String[] deletedUsers, final String[] newUsers)
            throws UserStoreException {
        try {
            AccessController.doPrivileged(new PrivilegedExceptionAction<String>() {
                @Override
                public String run() throws Exception {
                    updateUserListOfRoleInternal(roleName, deletedUsers, newUsers);
                    return null;
                }
            });
        } catch (PrivilegedActionException e) {
            throw (UserStoreException) e.getException();
        }
    }

    /**
     * {@inheritDoc}
     */
    private final void updateUserListOfRoleInternal(String roleName, String[] deletedUsers, String[] newUsers)
            throws UserStoreException {

        String primaryDomain = getMyDomainName();
        if (primaryDomain != null) {
            primaryDomain += CarbonConstants.DOMAIN_SEPARATOR;
        }

        if (deletedUsers != null && deletedUsers.length > 0) {
            Arrays.sort(deletedUsers);
            // Updating the user list of a role belong to the primary domain.
            if (UserCoreUtil.isPrimaryAdminRole(roleName, realmConfig)) {
                for (int i = 0; i < deletedUsers.length; i++) {
                    if (deletedUsers[i].equalsIgnoreCase(realmConfig.getAdminUserName())
                            || (primaryDomain + deletedUsers[i]).equalsIgnoreCase(realmConfig
                            .getAdminUserName())) {
                        throw new UserStoreException(REMOVE_ADMIN_USER + " Cannot remove Admin user from Admin role");
                    }

                }
            }
        }

        UserStore userStore = getUserStore(roleName);

        if (userStore.isHybridRole()) {
            // Check whether someone is trying to update Everyone role.
            if (UserCoreUtil.isEveryoneRole(roleName, realmConfig)) {
                throw new UserStoreException("Cannot update everyone role");
            }

            if (UserCoreConstants.INTERNAL_DOMAIN.equalsIgnoreCase(userStore.getDomainName())) {
                hybridRoleManager.updateUserListOfHybridRole(userStore.getDomainFreeName(),
                        deletedUsers, newUsers);
            } else {
                hybridRoleManager.updateUserListOfHybridRole(userStore.getDomainAwareName(),
                        deletedUsers, newUsers);
            }
            clearUserRolesCacheByTenant(this.tenantId);
            return;
        }

        if (userStore.isSystemStore()) {
            systemUserRoleManager.updateUserListOfSystemRole(userStore.getDomainFreeName(),
                    UserCoreUtil.removeDomainFromNames(deletedUsers),
                    UserCoreUtil.removeDomainFromNames(newUsers));
            return;
        }

        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().updateUserListOfRole(userStore.getDomainFreeName(),
                    UserCoreUtil.removeDomainFromNames(deletedUsers),
                    UserCoreUtil.removeDomainFromNames(newUsers));
            return;
        }

        // #################### Domain Name Free Zone Starts Here ################################
        if (deletedUsers == null) {
            deletedUsers = new String[0];
        }
        if (newUsers == null) {
            newUsers = new String[0];
        }
        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPreUpdateUserListOfRole(roleName, deletedUsers,
                    newUsers, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

        if (deletedUsers.length > 0 || newUsers.length > 0) {
            if (!isReadOnly() && writeGroupsEnabled) {
                doUpdateUserListOfRole(userStore.getDomainFreeName(),
                        UserCoreUtil.removeDomainFromNames(deletedUsers),
                        UserCoreUtil.removeDomainFromNames(newUsers));
            } else {
                throw new UserStoreException(
                        "Read-only user store.Roles cannot be added or modified");
            }
        }

        // need to clear user roles cache upon roles update
        clearUserRolesCacheByTenant(this.tenantId);

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPostUpdateUserListOfRole(roleName, deletedUsers,
                    newUsers, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

    }

    public final void updateRoleListOfUser(final String username, final String[] deletedRoles, final String[] newRoles)
            throws UserStoreException {
        try {
            AccessController.doPrivileged((PrivilegedExceptionAction<String>) () -> {
                updateRoleListOfUserInternal(username, deletedRoles, newRoles);
                return null;
            });
        } catch (PrivilegedActionException e) {
            throw (UserStoreException) e.getException();
        }
    }

    /**
     * {@inheritDoc}
     */
    private final void updateRoleListOfUserInternal(String userName, String[] deletedRoles, String[] newRoles)
            throws UserStoreException {

        String primaryDomain = realmConfig
                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);
        if (primaryDomain != null) {
            primaryDomain += CarbonConstants.DOMAIN_SEPARATOR;
        }

        if (deletedRoles != null && deletedRoles.length > 0) {
            Arrays.sort(deletedRoles);
            if (UserCoreUtil.isPrimaryAdminUser(userName, realmConfig)) {
                for (int i = 0; i < deletedRoles.length; i++) {
                    if (deletedRoles[i].equalsIgnoreCase(realmConfig.getAdminRoleName())
                            || (primaryDomain + deletedRoles[i]).equalsIgnoreCase(realmConfig
                            .getAdminRoleName())) {
                        throw new UserStoreException("Cannot remove Admin user from Admin role");
                    }
                }
            }
        }

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().updateRoleListOfUser(userStore.getDomainFreeName(),
                    UserCoreUtil.removeDomainFromNames(deletedRoles),
                    UserCoreUtil.removeDomainFromNames(newRoles));
            return;
        }

        if (userStore.isSystemStore()) {
            systemUserRoleManager.updateSystemRoleListOfUser(userStore.getDomainFreeName(),
                    UserCoreUtil.removeDomainFromNames(deletedRoles),
                    UserCoreUtil.removeDomainFromNames(newRoles));
            return;
        }

        // #################### Domain Name Free Zone Starts Here ################################
        if (deletedRoles == null) {
            deletedRoles = new String[0];
        }
        if (newRoles == null) {
            newRoles = new String[0];
        }
        // This happens only once during first startup - adding administrator user/role.
        if (userName.indexOf(CarbonConstants.DOMAIN_SEPARATOR) > 0) {
            userName = userStore.getDomainFreeName();
            deletedRoles = UserCoreUtil.removeDomainFromNames(deletedRoles);
            newRoles = UserCoreUtil.removeDomainFromNames(newRoles);
        }

        List<String> internalRoleDel = new ArrayList<>();
        List<String> internalRoleNew = new ArrayList<>();

        List<String> roleDel = new ArrayList<>();
        List<String> roleNew = new ArrayList<>();

        if (deletedRoles.length > 0) {
            for (String deleteRole : deletedRoles) {
                if (UserCoreUtil.isEveryoneRole(deleteRole, realmConfig)) {
                    throw new UserStoreException("Everyone role cannot be updated");
                }
                String domain = null;
                int index1 = deleteRole.indexOf(CarbonConstants.DOMAIN_SEPARATOR);
                if (index1 > 0) {
                    domain = deleteRole.substring(0, index1);
                }
                if (UserCoreConstants.INTERNAL_DOMAIN.equalsIgnoreCase(domain) || this.isReadOnly()) {
                    internalRoleDel.add(UserCoreUtil.removeDomainFromName(deleteRole));
                } else if (APPLICATION_DOMAIN.equalsIgnoreCase(domain) || WORKFLOW_DOMAIN.equalsIgnoreCase(domain)) {
                    internalRoleDel.add(deleteRole);
                } else {
                    // This is domain free role name.
                    roleDel.add(UserCoreUtil.removeDomainFromName(deleteRole));
                }
            }
            deletedRoles = roleDel.toArray(new String[roleDel.size()]);
        }

        if (newRoles.length > 0) {
            for (String newRole : newRoles) {
                if (UserCoreUtil.isEveryoneRole(newRole, realmConfig)) {
                    throw new UserStoreException("Everyone role cannot be updated");
                }
                String domain = null;
                int index2 = newRole.indexOf(CarbonConstants.DOMAIN_SEPARATOR);
                if (index2 > 0) {
                    domain = newRole.substring(0, index2);
                }
                if (UserCoreConstants.INTERNAL_DOMAIN.equalsIgnoreCase(domain) || this.isReadOnly()) {
                    internalRoleNew.add(UserCoreUtil.removeDomainFromName(newRole));
                } else if (APPLICATION_DOMAIN.equalsIgnoreCase(domain) || WORKFLOW_DOMAIN.equalsIgnoreCase(domain)) {
                    internalRoleNew.add(newRole);
                } else {
                    roleNew.add(UserCoreUtil.removeDomainFromName(newRole));
                }
            }
            newRoles = roleNew.toArray(new String[roleNew.size()]);
        }

        if (internalRoleDel.size() > 0 || internalRoleNew.size() > 0) {
            hybridRoleManager.updateHybridRoleListOfUser(userStore.getDomainFreeName(),
                    internalRoleDel.toArray(new String[internalRoleDel.size()]),
                    internalRoleNew.toArray(new String[internalRoleNew.size()]));
        }

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPreUpdateRoleListOfUser(userName, deletedRoles, newRoles, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

        if (deletedRoles.length > 0 || newRoles.length > 0) {
            if (!isReadOnly() && writeGroupsEnabled) {
                doUpdateRoleListOfUser(userName, deletedRoles, newRoles);
            } else {
                throw new UserStoreException("Read-only user store. Cannot add/modify roles.");
            }
        }

        clearUserRolesCache(UserCoreUtil.addDomainToName(userName, getMyDomainName()));

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPostUpdateRoleListOfUser(userName, deletedRoles, newRoles, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

    }

    /**
     * {@inheritDoc}
     */
    public final void updateRoleName(String roleName, String newRoleName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class};
            callSecure("updateRoleName", new Object[]{roleName, newRoleName}, argTypes);
            return;
        }

        if (UserCoreUtil.isPrimaryAdminRole(newRoleName, realmConfig)) {
            throw new UserStoreException("Cannot rename admin role");
        }

        if (UserCoreUtil.isEveryoneRole(newRoleName, realmConfig)) {
            throw new UserStoreException("Cannot rename everyone role");
        }

        UserStore userStore = getUserStore(roleName);
        UserStore userStoreNew = getUserStore(newRoleName);

        if (!UserCoreUtil.canRoleBeRenamed(userStore, userStoreNew, realmConfig)) {
            throw new UserStoreException("The role cannot be renamed");
        }

        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().updateRoleName(userStore.getDomainFreeName(),
                    userStoreNew.getDomainFreeName());
            return;
        }

        // #################### Domain Name Free Zone Starts Here ################################

        if (userStore.isHybridRole()) {
            if (UserCoreConstants.INTERNAL_DOMAIN.equalsIgnoreCase(userStore.getDomainName())) {
                hybridRoleManager.updateHybridRoleName(userStore.getDomainFreeName(),
                        userStoreNew.getDomainFreeName());
            } else {
                hybridRoleManager.updateHybridRoleName(userStore.getDomainAwareName(),
                        userStoreNew.getDomainAwareName());
            }

            // This is a special case. We need to pass roles with domains.
            userRealm.getAuthorizationManager().resetPermissionOnUpdateRole(
                    userStore.getDomainAwareName(), userStoreNew.getDomainAwareName());

            // Need to update user role cache upon update of role names
            clearUserRolesCacheByTenant(this.tenantId);
            return;
        }
//
//		RoleContext ctx = createRoleContext(roleName);
//        if (isOthersSharedRole(roleName)) {          // TODO do we need this
//            throw new UserStoreException(
//                    "Logged in user doesn't have permission to delete a role belong to other tenant");
//        }
        if (!isRoleNameValid(roleName)) {
            String regEx = realmConfig
                    .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_ROLE_NAME_JAVA_REG_EX);
            throw new UserStoreException(
                    INVALID_ROLE + " Role name not valid. Role name must be a non null string with following format, "
                            + regEx);
        }

        if (isExistingRole(newRoleName)) {
            throw new UserStoreException("Role name: " + newRoleName
                    + " in the system. Please pick another role name.");
        }

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPreUpdateRoleName(roleName, newRoleName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

        if (!isReadOnly() && writeGroupsEnabled) {
            doUpdateRoleName(userStore.getDomainFreeName(), userStoreNew.getDomainFreeName());
        } else {
            throw new UserStoreException(
                    READ_ONLY_STORE + " Read-only UserStoreManager. Roles cannot be added or modified.");
        }

        // This is a special case. We need to pass domain aware name.
        userRealm.getAuthorizationManager().resetPermissionOnUpdateRole(
                userStore.getDomainAwareName(), userStoreNew.getDomainAwareName());

        // need to update user role cache upon update of role names
        clearUserRolesCacheByTenant(tenantId);

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPostUpdateRoleName(roleName, newRoleName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

    }


    @Override
    public boolean isExistingRole(String roleName, boolean shared) throws org.wso2.carbon.user.api.UserStoreException {
        if (shared) {
            return isExistingShareRole(roleName);
        } else {
            return isExistingRole(roleName);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean isExistingRole(String roleName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class};
            Object object = callSecure("isExistingRole", new Object[]{roleName}, argTypes);
            return (Boolean) object;
        }

        UserStore userStore = getUserStore(roleName);

        if (userStore.isRecurssive()) {
            return userStore.getUserStoreManager().isExistingRole(userStore.getDomainFreeName());
        }

        // #################### Domain Name Free Zone Starts Here ################################

        if (userStore.isSystemStore()) {
            return systemUserRoleManager.isExistingRole(userStore.getDomainFreeName());
        }

        if (userStore.isHybridRole()) {
            boolean exist;

            if (!UserCoreConstants.INTERNAL_DOMAIN.equalsIgnoreCase(userStore.getDomainName())) {
                exist = hybridRoleManager.isExistingRole(userStore.getDomainAwareName());
            } else {
                exist = hybridRoleManager.isExistingRole(userStore.getDomainFreeName());
            }

            return exist;
        }

        // This happens only once during first startup - adding administrator user/role.
        roleName = userStore.getDomainFreeName();

        // you can not check existence of shared role using this method.
        if (isSharedGroupEnabled() && roleName.contains(UserCoreConstants.TENANT_DOMAIN_COMBINER)) {
            return false;
        }

        boolean isExisting = doCheckExistingRole(roleName);

        if (!isExisting && (isReadOnly() || !readGroupsEnabled)) {
            isExisting = hybridRoleManager.isExistingRole(roleName);
        }

        if (!isExisting) {
            if (systemUserRoleManager.isExistingRole(roleName)) {
                isExisting = true;
            }
        }

        return isExisting;
    }

//////////////////////////////////// Shared role APIs start //////////////////////////////////////////

    /**
     * TODO move to API
     *
     * @param roleName
     * @return
     * @throws UserStoreException
     */
    public boolean isExistingShareRole(String roleName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class};
            Object object = callSecure("isExistingShareRole", new Object[]{roleName}, argTypes);
            return (Boolean) object;
        }

        UserStoreManager manager = getUserStoreWithSharedRoles();

        if (manager == null) {
            throw new UserStoreException("Share Groups are not supported by this realm");
        }

        return ((AbstractUserStoreManager) manager).doCheckExistingRole(roleName);
    }

    /**
     * TODO  move to API
     *
     * @param roleName
     * @param deletedUsers
     * @param newUsers
     * @throws UserStoreException
     */
    public void updateUsersOfSharedRole(String roleName,
                                        String[] deletedUsers, String[] newUsers) throws UserStoreException {

        UserStoreManager manager = getUserStoreWithSharedRoles();

        if (manager == null) {
            throw new UserStoreException("Share Groups are not supported by this realm");
        }

        ((AbstractUserStoreManager) manager).doUpdateUserListOfRole(roleName, deletedUsers, newUsers);
    }

    /**
     * TODO move to API
     *
     * @return
     * @throws UserStoreException
     */
    public String[] getSharedRolesOfUser(String userName,
                                         String tenantDomain, String filter) throws UserStoreException {

        UserStore userStore = getUserStore(userName);
        UserStoreManager manager = userStore.getUserStoreManager();

        if (!((AbstractUserStoreManager) manager).isSharedGroupEnabled()) {
            throw new UserStoreException("Share Groups are not supported by user store");
        }

        String[] sharedRoles = ((AbstractUserStoreManager) manager).
                doGetSharedRoleListOfUser(userStore.getDomainFreeName(), tenantDomain, filter);
        return UserCoreUtil.removeDomainFromNames(sharedRoles);
    }

    /**
     * TODO move to API
     *
     * @return
     * @throws UserStoreException
     */
    public String[] getUsersOfSharedRole(String roleName, String filter) throws UserStoreException {

        UserStoreManager manager = getUserStoreWithSharedRoles();

        if (manager == null) {
            throw new UserStoreException("Share Groups are not supported by this realm");
        }

        String[] users = ((AbstractUserStoreManager) manager).doGetUserListOfRole(roleName, filter);
        return UserCoreUtil.removeDomainFromNames(users);
    }

    /**
     * TODO move to API
     *
     * @return
     * @throws UserStoreException
     */
    public String[] getSharedRoleNames(String tenantDomain, String filter,
                                       int maxItemLimit) throws UserStoreException {


        UserStoreManager manager = getUserStoreWithSharedRoles();

        if (manager == null) {
            throw new UserStoreException("Share Groups are not supported by this realm");
        }

        String[] sharedRoles = null;
        try {
            sharedRoles = ((AbstractUserStoreManager) manager).
                    doGetSharedRoleNames(tenantDomain, filter, maxItemLimit);
        } catch (UserStoreException e) {
            throw new UserStoreException("Error while retrieving shared roles", e);
        }
        return UserCoreUtil.removeDomainFromNames(sharedRoles);
    }


    /**
     * TODO move to API
     *
     * @return
     * @throws UserStoreException
     */
    public String[] getSharedRoleNames(String filter, int maxItemLimit) throws UserStoreException {

        UserStoreManager manager = getUserStoreWithSharedRoles();

        if (manager == null) {
            throw new UserStoreException("Share Groups are not supported by this realm");
        }

        String[] sharedRoles;
        try {
            sharedRoles = ((AbstractUserStoreManager) manager).
                    doGetSharedRoleNames(null, filter, maxItemLimit);
        } catch (UserStoreException e) {
            throw new UserStoreException("Error while retrieving shared roles", e);
        }
        return UserCoreUtil.removeDomainFromNames(sharedRoles);
    }


    public void addInternalRole(String roleName, String[] userList,
                                org.wso2.carbon.user.api.Permission[] permission) throws UserStoreException {
        doAddInternalRole(roleName, userList, permission);
    }

    private UserStoreManager getUserStoreWithSharedRoles() throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{};
            Object object = callSecure("getUserStoreWithSharedRoles", new Object[]{}, argTypes);
            return (UserStoreManager) object;
        }

        UserStoreManager sharedRoleManager = null;

        if (isSharedGroupEnabled()) {
            return this;
        }

        for (Map.Entry<String, UserStoreManager> entry : userStoreManagerHolder.entrySet()) {
            UserStoreManager manager = entry.getValue();
            if (manager != null && ((AbstractUserStoreManager) manager).isSharedGroupEnabled()) {
                if (sharedRoleManager != null) {
                    throw new UserStoreException("There can not be more than one user store that support" +
                            "shared groups");
                }
                sharedRoleManager = manager;
            }
        }

        return sharedRoleManager;
    }

    /**
     * TODO move to API
     *
     * @param userName
     * @param roleName
     * @return
     * @throws UserStoreException
     */
    public boolean isUserInRole(String userName, String roleName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class};
            Object object = callSecure("isUserInRole", new Object[]{userName, roleName}, argTypes);
            return (Boolean) object;
        }

        if (roleName == null || roleName.trim().length() == 0 || userName == null ||
                userName.trim().length() == 0) {
            return false;
        }

        // anonymous user is always assigned to  anonymous role
        if (CarbonConstants.REGISTRY_ANONNYMOUS_ROLE_NAME.equalsIgnoreCase(roleName) &&
                CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME.equalsIgnoreCase(userName)) {
            return true;
        }

        if (!CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME.equalsIgnoreCase(userName) &&
                realmConfig.getEveryOneRoleName().equalsIgnoreCase(roleName) &&
                !systemUserRoleManager.isExistingSystemUser(UserCoreUtil.
                        removeDomainFromName(userName))) {
            return true;
        }


        String[] roles = null;

        roles = getRoleListOfUserFromCache(tenantId, userName);
        if (roles != null && roles.length > 0) {
            if (UserCoreUtil.isContain(roleName, roles)) {
                return true;
            }
        }

        // TODO create new cache for this method
        String modifiedUserName = UserCoreConstants.IS_USER_IN_ROLE_CACHE_IDENTIFIER + userName;
        roles = getRoleListOfUserFromCache(tenantId, modifiedUserName);
        if (roles != null && roles.length > 0) {
            if (UserCoreUtil.isContain(roleName, roles)) {
                return true;
            }
        }

        if (UserCoreConstants.INTERNAL_DOMAIN.
                equalsIgnoreCase(UserCoreUtil.extractDomainFromName(roleName))
                || APPLICATION_DOMAIN.equalsIgnoreCase(UserCoreUtil.extractDomainFromName(roleName)) ||
                WORKFLOW_DOMAIN.equalsIgnoreCase(UserCoreUtil.extractDomainFromName(roleName))) {

            String[] internalRoles = doGetInternalRoleListOfUser(userName, "*");
            if (UserCoreUtil.isContain(roleName, internalRoles)) {
                addToIsUserHasRole(modifiedUserName, roleName, roles);
                return true;
            }
        }

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive()
                && (userStore.getUserStoreManager() instanceof AbstractUserStoreManager)) {
            return ((AbstractUserStoreManager) userStore.getUserStoreManager()).isUserInRole(
                    userStore.getDomainFreeName(), roleName);
        }

        // #################### Domain Name Free Zone Starts Here ################################

        if (userStore.isSystemStore()) {
            return systemUserRoleManager.isUserInRole(userStore.getDomainFreeName(),
                    UserCoreUtil.removeDomainFromName(roleName));
        }
        // admin user is always assigned to admin role if it is in primary user store
        if (realmConfig.isPrimary() && roleName.equalsIgnoreCase(realmConfig.getAdminRoleName()) &&
                userName.equalsIgnoreCase(realmConfig.getAdminUserName())) {
            return true;
        }

        String roleDomainName = UserCoreUtil.extractDomainFromName(roleName);

        String roleDomainNameForForest = realmConfig.
                getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_GROUP_SEARCH_DOMAINS);
        if (roleDomainNameForForest != null && roleDomainNameForForest.trim().length() > 0) {
            String[] values = roleDomainNameForForest.split("#");
            for (String value : values) {
                if (value != null && !value.trim().equalsIgnoreCase(roleDomainName)) {
                    return false;
                }
            }
        } else if (!userStore.getDomainName().equalsIgnoreCase(roleDomainName)) {
            return false;
        }

        boolean success = false;
        if (readGroupsEnabled) {
            success = doCheckIsUserInRole(userStore.getDomainFreeName(),
                    UserCoreUtil.removeDomainFromName(roleName));
        }

        // add to cache
        if (success) {
            addToIsUserHasRole(modifiedUserName, roleName, roles);
        }
        return success;
    }

    /**
     * @param userName
     * @param roleName
     * @return
     * @throws UserStoreException
     */
    public abstract boolean doCheckIsUserInRole(String userName, String roleName) throws UserStoreException;

    /**
     * Helper method
     *
     * @param userName
     * @param roleName
     * @param currentRoles
     */
    private void addToIsUserHasRole(String userName, String roleName, String[] currentRoles) {
        List<String> roles;
        if (currentRoles != null) {
            roles = new ArrayList<String>(Arrays.asList(currentRoles));
        } else {
            roles = new ArrayList<String>();
        }
        roles.add(roleName);
        addToUserRolesCache(tenantId, userName, roles.toArray(new String[roles.size()]));
    }

//////////////////////////////////// Shared role APIs finish //////////////////////////////////////////

    /**
     * {@inheritDoc}
     */
    public boolean isExistingUser(String userName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class};
            Object object = callSecure("isExistingUser", new Object[]{userName}, argTypes);
            return (Boolean) object;
        }

        if (UserCoreUtil.isRegistrySystemUser(userName)) {
            return true;
        }

        UserStore userStore = getUserStore(userName);
        if (userStore.isRecurssive()) {
            return userStore.getUserStoreManager().isExistingUser(userStore.getDomainFreeName());
        }

        // #################### Domain Name Free Zone Starts Here ################################

        if (userStore.isSystemStore()) {
            return systemUserRoleManager.isExistingSystemUser(userStore.getDomainFreeName());
        }


        return doCheckExistingUser(userStore.getDomainFreeName());

    }

    /**
     * {@inheritDoc}
     */
    public final String[] listUsers(String filter, int maxItemLimit) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, int.class};
            Object object = callSecure("listUsers", new Object[]{filter, maxItemLimit}, argTypes);
            return (String[]) object;
        }

        int index;
        index = filter.indexOf(CarbonConstants.DOMAIN_SEPARATOR);

        // Check whether we have a secondary UserStoreManager setup.
        if (index > 0) {
            // Using the short-circuit. User name comes with the domain name.
            String domain = filter.substring(0, index);

            UserStoreManager secManager = getSecondaryUserStoreManager(domain);
            if (secManager != null) {
                // We have a secondary UserStoreManager registered for this domain.
                filter = filter.substring(index + 1);
                if (secManager instanceof AbstractUserStoreManager) {
                    return ((AbstractUserStoreManager) secManager)
                            .doListUsers(filter, maxItemLimit);
                } else {
                    return secManager.listUsers(filter, maxItemLimit);
                }
            } else {
                // Exception is not need to as listing of users
                // throw new UserStoreException("Invalid Domain Name");
            }
        } else if (index == 0) {
            return doListUsers(filter.substring(index + 1), maxItemLimit);
        }

        String[] userList = doListUsers(filter, maxItemLimit);

        String primaryDomain = realmConfig
                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);

        if (this.getSecondaryUserStoreManager() != null) {
            for (Map.Entry<String, UserStoreManager> entry : userStoreManagerHolder.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(primaryDomain)) {
                    continue;
                }
                UserStoreManager storeManager = entry.getValue();
                if (storeManager instanceof AbstractUserStoreManager) {
                    try {
                        String[] secondUserList = ((AbstractUserStoreManager) storeManager)
                                .doListUsers(filter, maxItemLimit);
                        userList = UserCoreUtil.combineArrays(userList, secondUserList);
                    } catch (UserStoreException ex) {
                        // We can ignore and proceed. Ignore the results from this user store.
                        log.error(ex);
                    }
                } else {
                    String[] secondUserList = storeManager.listUsers(filter, maxItemLimit);
                    userList = UserCoreUtil.combineArrays(userList, secondUserList);
                }
            }
        }

        return userList;
    }

    /**
     * {@inheritDoc}
     */
    public final String[] getUserListOfRole(String roleName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class};
            Object object = callSecure("getUserListOfRole", new Object[]{roleName}, argTypes);
            return (String[]) object;
        }

        String[] userNames = new String[0];

        // If role does not exit, just return
        if (!isExistingRole(roleName)) {
            return userNames;
        }

        UserStore userStore = getUserStore(roleName);

        if (userStore.isRecurssive()) {
            return userStore.getUserStoreManager().getUserListOfRole(userStore.getDomainFreeName());
        }


        // #################### Domain Name Free Zone Starts Here
        // ################################

        if (userStore.isSystemStore()) {
            return systemUserRoleManager.getUserListOfSystemRole(userStore.getDomainFreeName());
        }

        String[] userNamesInHybrid = new String[0];
        if (userStore.isHybridRole()) {
            if (UserCoreConstants.INTERNAL_DOMAIN.equalsIgnoreCase(userStore.getDomainName())) {
                userNamesInHybrid =
                        hybridRoleManager.getUserListOfHybridRole(userStore.getDomainFreeName());
            } else {
                userNamesInHybrid = hybridRoleManager.getUserListOfHybridRole(userStore.getDomainAwareName());
            }

            // remove domain
            List<String> finalNameList = new ArrayList<String>();
            String displayNameAttribute =
                    this.realmConfig.getUserStoreProperty(LDAPConstants.DISPLAY_NAME_ATTRIBUTE);

            if (userNamesInHybrid != null && userNamesInHybrid.length > 0) {
                if (displayNameAttribute != null && displayNameAttribute.trim().length() > 0) {
                    for (String userName : userNamesInHybrid) {
                        String domainName = UserCoreUtil.extractDomainFromName(userName);
                        if (domainName == null || domainName.trim().length() == 0) {
                            finalNameList.add(userName);
                        }
                        UserStoreManager userManager = userStoreManagerHolder.get(domainName);
                        userName = UserCoreUtil.removeDomainFromName(userName);
                        if (userManager != null) {
                            String[] displayNames = null;
                            if (userManager instanceof AbstractUserStoreManager) {
                                // get displayNames
                                displayNames = ((AbstractUserStoreManager) userManager)
                                        .doGetDisplayNamesForInternalRole(new String[]{userName});
                            } else {
                                displayNames = userManager.getRoleNames();
                            }

                            for (String displayName : displayNames) {
                                // if domain names are not added by above method, add it
                                // here
                                String nameWithDomain = UserCoreUtil.addDomainToName(displayName, domainName);
                                finalNameList.add(nameWithDomain);
                            }
                        }
                    }
                } else {
                    return userNamesInHybrid;
                }
            }
            return finalNameList.toArray(new String[finalNameList.size()]);
            // return
            // hybridRoleManager.getUserListOfHybridRole(userStore.getDomainFreeName());
        }

        if (readGroupsEnabled) {
            userNames = doGetUserListOfRole(roleName, "*");
        }

        return userNames;
    }

    @Deprecated
    public String[] getRoleListOfUser(String userName) throws UserStoreException {

        User user = new UserImpl();
        user.setUserName(userName);
        return getRoleListOfUser(user);
    }

    public String[] getRoleListOfUser(User user) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{User.class};
            Object object = callSecure("getRoleListOfUser", new Object[]{user}, argTypes);
            return (String[]) object;
        }

        String[] roleNames;

        // anonymous user is only assigned to  anonymous role
        if (CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME.equalsIgnoreCase(user.getName())) {
            return new String[]{CarbonConstants.REGISTRY_ANONNYMOUS_ROLE_NAME};
        }

        String usernameWithDomain = UserCoreUtil.addDomainToName(user.getUsername(), getMyDomainName());
        // Check whether roles exist in cache
        roleNames = getRoleListOfUserFromCache(this.tenantId, usernameWithDomain);
        if (roleNames != null && roleNames.length > 0) {
            return roleNames;
        }

        UserStore userStore = getUserStore(user.getId());
        if (userStore.isRecurssive()) {
            return userStore.getUserStoreManager().getRoleListOfUser(userStore.getDomainFreeName());
        }

        if (userStore.isSystemStore()) {
            return systemUserRoleManager.getSystemRoleListOfUser(userStore.getDomainFreeName());
        }
        // #################### Domain Name Free Zone Starts Here ################################

        roleNames = doGetRoleListOfUser(user, "*");

        return roleNames;

    }

    /**
     * Getter method for claim manager property specifically to be used in the implementations of
     * UserOperationEventListener implementations
     *
     * @return
     */
    public ClaimManager getClaimManager() {
        return claimManager;
    }

    /**
     *
     */
    public void addRole(String roleName, String[] userList,
                        org.wso2.carbon.user.api.Permission[] permissions, boolean isSharedRole)
            throws org.wso2.carbon.user.api.UserStoreException {

        UserStore userStore = getUserStore(roleName);

        if (isSharedRole && !isSharedGroupEnabled()) {
            throw new org.wso2.carbon.user.api.UserStoreException(
                    SHARED_USER_ROLES + "User store doesn't support shared user roles functionality");
        }

        if (userStore.isHybridRole()) {
            doAddInternalRole(roleName, userList, permissions);
            return;
        }

        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().addRole(userStore.getDomainFreeName(),
                    UserCoreUtil.removeDomainFromNames(userList), permissions, isSharedRole);
            return;
        }

        // #################### Domain Name Free Zone Starts Here ################################
        if (userList == null) {
            userList = new String[0];
        }
        if (permissions == null) {
            permissions = new org.wso2.carbon.user.api.Permission[0];
        }
        // This happens only once during first startup - adding administrator user/role.
        if (roleName.indexOf(CarbonConstants.DOMAIN_SEPARATOR) > 0) {
            roleName = userStore.getDomainFreeName();
            userList = UserCoreUtil.removeDomainFromNames(userList);
        }


        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPreAddRole(roleName, userList, permissions, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

        // Check for validations
        if (isReadOnly()) {
            throw new UserStoreException(
                    READ_ONLY_PRIMARY_STORE + " Cannot add role to Read Only user store unless it is primary");
        }

        if (!isRoleNameValid(roleName)) {
            String regEx = realmConfig
                    .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_ROLE_NAME_JAVA_REG_EX);
            throw new UserStoreException(
                    INVALID_ROLE + " Role name not valid. Role name must be a non null string with following format, "
                            + regEx);
        }

        if (doCheckExistingRole(roleName)) {
            throw new UserStoreException(EXISTING_ROLE + " Role name: " + roleName +
                    " in the system. Please pick another role name.");
        }

        String roleWithDomain = null;
        if (!isReadOnly() && writeGroupsEnabled) {
            // add role in to actual user store
            doAddRole(roleName, userList, isSharedRole);

            roleWithDomain = UserCoreUtil.addDomainToName(roleName, getMyDomainName());
        } else {
            throw new UserStoreException(
                    NO_READ_WRITE_PERMISSIONS + " Role cannot be added. User store is read only or cannot write groups.");
        }

        // add permission in to the the permission store
        if (permissions != null) {
            for (org.wso2.carbon.user.api.Permission permission : permissions) {
                String resourceId = permission.getResourceId();
                String action = permission.getAction();
                if (resourceId == null || resourceId.trim().length() == 0) {
                    continue;
                }

                if (action == null || action.trim().length() == 0) {
                    // default action value // TODO
                    action = "read";
                }
                // This is a special case. We need to pass domain aware name.
                userRealm.getAuthorizationManager().authorizeRole(roleWithDomain, resourceId,
                        action);
            }
        }

        // if existing users are added to role, need to update user role cache
        if ((userList != null) && (userList.length > 0)) {
            clearUserRolesCacheByTenant(tenantId);
        }

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPostAddRole(roleName, userList, permissions, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

    }

    /**
     * TODO move to API
     *
     * @return
     */
    public boolean isSharedGroupEnabled() {
        String value = realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig.SHARED_GROUPS_ENABLED);
        try {
            return realmConfig.isPrimary() && !isReadOnly() && TRUE_VALUE.equalsIgnoreCase(value);
        } catch (UserStoreException e) {
            log.error(e);
        }
        return false;
    }

    /**
     * Removes the shared roles relevant to the provided tenant domain
     *
     * @param sharedRoles
     * @param tenantDomain
     */
    protected void filterSharedRoles(List<String> sharedRoles, String tenantDomain) {
        if (tenantDomain != null) {
            for (Iterator<String> i = sharedRoles.iterator(); i.hasNext(); ) {
                String role = i.next();
                if (role.indexOf(tenantDomain) > -1) {
                    i.remove();
                }
            }
        }
    }

    /**
     * Delete the role with the given role name
     *
     * @param roleName The role name
     * @throws org.wso2.carbon.user.core.UserStoreException
     */
    public final void deleteRole(String roleName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class};
            callSecure("deleteRole", new Object[]{roleName}, argTypes);
            return;
        }

        if (UserCoreUtil.isPrimaryAdminRole(roleName, realmConfig)) {
            throw new UserStoreException("Cannot delete admin role");
        }
        if (UserCoreUtil.isEveryoneRole(roleName, realmConfig)) {
            throw new UserStoreException("Cannot delete everyone role");
        }

        UserStore userStore = getUserStore(roleName);
        if (userStore.isRecurssive()) {
            userStore.getUserStoreManager().deleteRole(userStore.getDomainFreeName());
            return;
        }

        String roleWithDomain = UserCoreUtil.addDomainToName(roleName, getMyDomainName());
        // #################### Domain Name Free Zone Starts Here ################################

        if (userStore.isHybridRole()) {
            if (APPLICATION_DOMAIN.equalsIgnoreCase(userStore.getDomainName()) ||
                    WORKFLOW_DOMAIN.equalsIgnoreCase(userStore.getDomainName())) {
                hybridRoleManager.deleteHybridRole(roleName);
            } else {
                hybridRoleManager.deleteHybridRole(userStore.getDomainFreeName());
            }
            clearUserRolesCacheByTenant(tenantId);
            return;
        }
//
//		RoleContext ctx = createRoleContext(roleName);
//		if (isOthersSharedRole(roleName)) {
//			throw new UserStoreException(
//			                             "Logged in user doesn't have permission to delete a role belong to other tenant");
//		}


        if (!doCheckExistingRole(roleName)) {
            throw new UserStoreException("Can not delete non exiting role");
        }

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPreDeleteRole(roleName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

        if (!isReadOnly() && writeGroupsEnabled) {
            doDeleteRole(roleName);
        } else {
            throw new UserStoreException(
                    "Role cannot be deleted. User store is read only or cannot write groups.");
        }

        // clear role authorization
        userRealm.getAuthorizationManager().clearRoleAuthorization(roleWithDomain);

        // clear cache
        clearUserRolesCacheByTenant(tenantId);

        // #################### <Listeners> #####################################################
        for (UserOperationEventListener listener : UMListenerServiceComponent
                .getUserOperationEventListeners()) {
            if (!listener.doPostDeleteRole(roleName, this)) {
                return;
            }
        }
        // #################### </Listeners> #####################################################

    }

    /**
     * Method to get the password expiration time.
     *
     * @param userName the user name.
     * @return the password expiration time.
     * @throws UserStoreException throw if the operation failed.
     */
    @Override
    @Deprecated
    public Date getPasswordExpirationTime(String userName) throws UserStoreException {

        UserStore userStore = getUserStore(userName);

        if (userStore.isRecurssive()) {
            return userStore.getUserStoreManager().getPasswordExpirationTime(userStore.getDomainFreeName());
        }

        return null;
    }

    public Date getPasswordExpirationTime(User user) throws UserStoreException {

        UserStore userStore = getUserStore(user.getName());

        if (userStore.isRecurssive()) {
            return userStore.getUserStoreManager().getPasswordExpirationTime(userStore.getDomainFreeName());
        }

        return null;
    }

    private UserStore getUserStore(final String user) throws UserStoreException {
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<UserStore>() {
                @Override
                public UserStore run() throws Exception {
                    return getUserStoreInternal(user);
                }
            });
        } catch (PrivilegedActionException e) {
            throw (UserStoreException) e.getException();
        }
    }

    /**
     * @return
     * @throws UserStoreException
     */
    private UserStore getUserStoreInternal(String user) throws UserStoreException {

        int index;
        index = user.indexOf(CarbonConstants.DOMAIN_SEPARATOR);
        UserStore userStore = new UserStore();
        String domainFreeName = null;

        // Check whether we have a secondary UserStoreManager setup.
        if (index > 0) {
            // Using the short-circuit. User name comes with the domain name.
            String domain = user.substring(0, index);
            UserStoreManager secManager = getSecondaryUserStoreManager(domain);
            domainFreeName = user.substring(index + 1);

            if (secManager != null) {
                userStore.setUserStoreManager(secManager);
                userStore.setDomainAwareName(user);
                userStore.setDomainFreeName(domainFreeName);
                userStore.setDomainName(domain);
                userStore.setRecurssive(true);
                return userStore;
            } else {
                if (!domain.equalsIgnoreCase(getMyDomainName())) {
                    if ((UserCoreConstants.INTERNAL_DOMAIN.equalsIgnoreCase(domain)
                            || APPLICATION_DOMAIN.equalsIgnoreCase(domain) || WORKFLOW_DOMAIN.equalsIgnoreCase(domain))) {
                        userStore.setHybridRole(true);
                    } else if (UserCoreConstants.SYSTEM_DOMAIN_NAME.equalsIgnoreCase(domain)) {
                        userStore.setSystemStore(true);
                    } else {
                        throw new UserStoreException("Invalid Domain Name");
                    }
                }

                userStore.setDomainAwareName(user);
                userStore.setDomainFreeName(domainFreeName);
                userStore.setDomainName(domain);
                userStore.setRecurssive(false);
                return userStore;
            }
        }

        String domain = getMyDomainName();
        userStore.setUserStoreManager(this);
        if (index > 0) {
            userStore.setDomainAwareName(user);
            userStore.setDomainFreeName(domainFreeName);
        } else {
            userStore.setDomainAwareName(domain + CarbonConstants.DOMAIN_SEPARATOR + user);
            userStore.setDomainFreeName(user);
        }
        userStore.setRecurssive(false);
        userStore.setDomainName(domain);

        return userStore;
    }

    /**
     * {@inheritDoc}
     */
    public final UserStoreManager getSecondaryUserStoreManager() {
        return secondaryUserStoreManager;
    }

    /**
     *
     */
    public final void setSecondaryUserStoreManager(UserStoreManager secondaryUserStoreManager) {
        this.secondaryUserStoreManager = secondaryUserStoreManager;
    }

    /**
     * {@inheritDoc}
     */
    public final UserStoreManager getSecondaryUserStoreManager(String userDomain) {
        if (userDomain == null) {
            return null;
        }
        return userStoreManagerHolder.get(userDomain.toUpperCase());
    }

    /**
     * {@inheritDoc}
     */
    public final void addSecondaryUserStoreManager(String userDomain,
                                                   UserStoreManager userStoreManager) {
        if (userDomain != null) {
            userStoreManagerHolder.put(userDomain.toUpperCase(), userStoreManager);
        }
    }

    public final void clearAllSecondaryUserStores() {
        userStoreManagerHolder.clear();

        if (getMyDomainName() != null) {
            userStoreManagerHolder.put(getMyDomainName().toUpperCase(), this);
        }
    }

    /**
     * {@inheritDoc}
     */
    public final String[] getAllSecondaryRoles() throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{};
            Object object = callSecure("getAllSecondaryRoles", new Object[]{}, argTypes);
            return (String[]) object;
        }

        UserStoreManager secondary = this.getSecondaryUserStoreManager();
        List<String> roleList = new ArrayList<String>();
        while (secondary != null) {
            String[] roles = secondary.getRoleNames(true);
            if (roles != null && roles.length > 0) {
                Collections.addAll(roleList, roles);
            }
            secondary = secondary.getSecondaryUserStoreManager();
        }
        return roleList.toArray(new String[roleList.size()]);
    }

    /**
     * @return
     */
    public boolean isSCIMEnabled() {
        String scimEnabled = realmConfig
                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_SCIM_ENABLED);
        if (scimEnabled != null) {
            return Boolean.parseBoolean(scimEnabled);
        } else {
            return false;
        }
    }

    /**
     * {@inheritDoc}                  doAddInternalRole
     */
    public final String[] getHybridRoles() throws UserStoreException {
        return hybridRoleManager.getHybridRoles("*");
    }

    /**
     * {@inheritDoc}
     */
    public final String[] getRoleNames() throws UserStoreException {
        return getRoleNames(false);
    }

    /**
     * {@inheritDoc}
     */
    public final String[] getRoleNames(boolean noHybridRoles) throws UserStoreException {
        return getRoleNames("*", MAX_ITEM_LIMIT_UNLIMITED, noHybridRoles, true, true);
    }

    /**
     * @param roleName
     * @param userList
     * @param permissions
     * @throws UserStoreException
     */
    protected void doAddInternalRole(String roleName, String[] userList,
                                     org.wso2.carbon.user.api.Permission[] permissions)
            throws UserStoreException {

        // #################### Domain Name Free Zone Starts Here ################################

        if (roleName.contains(UserCoreConstants.DOMAIN_SEPARATOR)
                && roleName.toLowerCase().startsWith(APPLICATION_DOMAIN.toLowerCase())) {
            if (hybridRoleManager.isExistingRole(roleName)) {
                throw new UserStoreException("Role name: " + roleName
                        + " in the system. Please pick another role name.");
            }

            hybridRoleManager.addHybridRole(roleName, userList);

        } else {
            if (hybridRoleManager.isExistingRole(UserCoreUtil.removeDomainFromName(roleName))) {
                throw new UserStoreException("Role name: " + roleName
                        + " in the system. Please pick another role name.");
            }

            hybridRoleManager.addHybridRole(UserCoreUtil.removeDomainFromName(roleName), userList);
        }


        if (permissions != null) {
            for (org.wso2.carbon.user.api.Permission permission : permissions) {
                String resourceId = permission.getResourceId();
                String action = permission.getAction();
                // This is a special case. We need to pass domain aware name.
                userRealm.getAuthorizationManager().authorizeRole(
                        UserCoreUtil.addInternalDomainName(roleName), resourceId, action);
            }
        }

        if ((userList != null) && (userList.length > 0)) {
            clearUserRolesCacheByTenant(this.tenantId);
        }
    }

    /**
     * Returns the set of shared roles which applicable for the logged in tenant
     *
     * @param tenantDomain tenant domain of the shared roles. If this is null,
     *                     returns all shared roles of available tenant domains
     * @param filter
     * @param maxItemLimit
     * @return
     */
    protected abstract String[] doGetSharedRoleNames(String tenantDomain, String filter,
                                                     int maxItemLimit) throws UserStoreException;

    /**
     * TODO This method would returns the role Name actually this must be implemented in interface.
     * As it is not good to change the API in point release. This has been added to Abstract class
     *
     * @param filter
     * @param maxItemLimit
     * @param noInternalRoles
     * @return
     * @throws UserStoreException
     */
    public final String[] getRoleNames(String filter, int maxItemLimit, boolean noInternalRoles,
                                       boolean noSystemRole, boolean noSharedRoles)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, int.class, boolean.class, boolean.class, boolean.class};
            Object object = callSecure("getRoleNames", new Object[]{filter, maxItemLimit, noInternalRoles,
                    noSystemRole, noSharedRoles}, argTypes);
            return (String[]) object;
        }

        String[] roleList = new String[0];

        if (!noInternalRoles && (filter.toLowerCase().startsWith(APPLICATION_DOMAIN.toLowerCase()))) {
            roleList = hybridRoleManager.getHybridRoles(filter);
        } else if (!noInternalRoles) {
            roleList = hybridRoleManager.getHybridRoles(UserCoreUtil.removeDomainFromName(filter));
        }

        if (!noSystemRole) {
            String[] systemRoles = systemUserRoleManager.getSystemRoles();
            roleList = UserCoreUtil.combineArrays(roleList, systemRoles);
        }

        int index;
        index = filter.indexOf(CarbonConstants.DOMAIN_SEPARATOR);

        // Check whether we have a secondary UserStoreManager setup.
        if (index > 0) {
            // Using the short-circuit. User name comes with the domain name.
            String domain = filter.substring(0, index);

            UserStoreManager secManager = getSecondaryUserStoreManager(domain);
            if (UserCoreConstants.INTERNAL_DOMAIN.equalsIgnoreCase(domain)
                    || APPLICATION_DOMAIN.equalsIgnoreCase(domain) || WORKFLOW_DOMAIN.equalsIgnoreCase(domain)) {
                return new String[0];
            }
            if (secManager != null) {
                // We have a secondary UserStoreManager registered for this domain.
                filter = filter.substring(index + 1);
                if (secManager instanceof AbstractUserStoreManager) {
                    if (readGroupsEnabled) {
                        String[] externalRoles = ((AbstractUserStoreManager) secManager)
                                .doGetRoleNames(filter, maxItemLimit);
                        return UserCoreUtil.combineArrays(roleList, externalRoles);
                    }
                } else {
                    String[] externalRoles = secManager.getRoleNames();
                    return UserCoreUtil.combineArrays(roleList, externalRoles);
                }
            } else {
                throw new UserStoreException("Invalid Domain Name");
            }
        } else if (index == 0) {
            if (readGroupsEnabled) {
                String[] externalRoles = doGetRoleNames(filter.substring(index + 1), maxItemLimit);
                return UserCoreUtil.combineArrays(roleList, externalRoles);
            }
        }

        if (readGroupsEnabled) {
            String[] externalRoles = doGetRoleNames(filter, maxItemLimit);
            roleList = UserCoreUtil.combineArrays(externalRoles, roleList);
        }

        String primaryDomain = getMyDomainName();

        if (this.getSecondaryUserStoreManager() != null) {
            for (Map.Entry<String, UserStoreManager> entry : userStoreManagerHolder.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(primaryDomain)) {
                    continue;
                }
                UserStoreManager storeManager = entry.getValue();
                if (storeManager instanceof AbstractUserStoreManager) {
                    try {
                        if (readGroupsEnabled) {
                            String[] secondRoleList = ((AbstractUserStoreManager) storeManager)
                                    .doGetRoleNames(filter, maxItemLimit);
                            roleList = UserCoreUtil.combineArrays(roleList, secondRoleList);
                        }
                    } catch (UserStoreException e) {
                        // We can ignore and proceed. Ignore the results from this user store.
                        log.error(e);
                    }
                } else {
                    roleList = UserCoreUtil.combineArrays(roleList, storeManager.getRoleNames());
                }
            }
        }
        return roleList;
    }

    /**
     * Get claim values for given user.
     * @param user User object.
     * @param claims
     * @param domainName
     * @return
     * @throws UserStoreException
     */
    private Map<String, String> doGetUserClaimValues(User user, String[] claims,
                                                     String domainName, String profileName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{User.class, String[].class, String.class, String.class};
            Object object = callSecure("doGetUserClaimValues", new Object[]{user, claims, domainName,
                    profileName}, argTypes);
            return (Map<String, String>) object;
        }

        // Here the user name should be domain-less.
        boolean requireRoles = false;
        boolean requireIntRoles = false;
        boolean requireExtRoles = false;
        String roleClaim = null;

        if (profileName == null || profileName.trim().length() == 0) {
            profileName = UserCoreConstants.DEFAULT_PROFILE;
        }

        Set<String> propertySet = new HashSet<>();
        for (String claim : claims) {

            // There can be cases some claim values being requested for claims
            // we don't have.
            String property;
            try {
                property = getClaimAtrribute(claim, user.getName(), domainName);
            } catch (org.wso2.carbon.user.api.UserStoreException e) {
                throw new UserStoreException(e);
            }
            if (property != null
                    && (!UserCoreConstants.ROLE_CLAIM.equalsIgnoreCase(claim)
                    || !UserCoreConstants.INT_ROLE_CLAIM.equalsIgnoreCase(claim) ||
                    !UserCoreConstants.EXT_ROLE_CLAIM.equalsIgnoreCase(claim))) {
                propertySet.add(property);
            }

            if (UserCoreConstants.ROLE_CLAIM.equalsIgnoreCase(claim)) {
                requireRoles = true;
                roleClaim = claim;
            } else if (UserCoreConstants.INT_ROLE_CLAIM.equalsIgnoreCase(claim)) {
                requireIntRoles = true;
                roleClaim = claim;
            } else if (UserCoreConstants.EXT_ROLE_CLAIM.equalsIgnoreCase(claim)) {
                requireExtRoles = true;
                roleClaim = claim;
            }
        }

        String[] properties = propertySet.toArray(new String[propertySet.size()]);
        Map<String, String> uerProperties = this.getUserPropertyValues(user.getName(), properties, profileName);

        Map<String, String> finalValues = new HashMap<>();

        for (String claim : claims) {
            ClaimMapping mapping;
            try {
                mapping = (ClaimMapping) claimManager.getClaimMapping(claim);
            } catch (org.wso2.carbon.user.api.UserStoreException e) {
                throw new UserStoreException(e);
            }
            String property = null;
            String value;
            if (mapping != null) {
                if (domainName != null) {
                    Map<String, String> attrMap = mapping.getMappedAttributes();
                    if (attrMap != null) {
                        String attr;
                        if ((attr = attrMap.get(domainName.toUpperCase())) != null) {
                            property = attr;
                        } else {
                            property = mapping.getMappedAttribute();
                        }
                    }
                } else {
                    property = mapping.getMappedAttribute();
                }

                value = uerProperties.get(property);

                if (profileName.equals(UserCoreConstants.DEFAULT_PROFILE)) {

                    // Check whether we have a value for the requested attribute
                    if (value != null && value.trim().length() > 0) {
                        finalValues.put(claim, value);
                    }
                } else {
                    if (value != null && value.trim().length() > 0) {
                        finalValues.put(claim, value);
                    }
                }
            } else {
                if (claim.equals(DISAPLAY_NAME_CLAIM)) {
                    property = this.realmConfig.getUserStoreProperty(LDAPConstants.DISPLAY_NAME_ATTRIBUTE);
                }

                value = uerProperties.get(property);
                if (value != null && value.trim().length() > 0) {
                    finalValues.put(claim, value);
                }
            }
        }

        // We treat roles claim in special way.
        String[] roles = null;

        if (requireRoles) {
            roles = getRoleListOfUser(user);
        } else if (requireIntRoles) {
            roles = doGetInternalRoleListOfUser(user, "*");
        } else if (requireExtRoles) {
            List<String> rolesList = new ArrayList<>();
            String[] externalRoles = doGetExternalRoleListOfUser(user.getUsername(), "*");
            rolesList.addAll(Arrays.asList(externalRoles));

            // If only shared enable.
            if (isSharedGroupEnabled()) {
                String[] sharedRoles = doGetSharedRoleListOfUser(user.getUsername(), null, "*");
                if (sharedRoles != null) {
                    rolesList.addAll(Arrays.asList(sharedRoles));
                }
            }

            roles = rolesList.toArray(new String[rolesList.size()]);
        }

        if (roles != null && roles.length > 0) {
            String userAttributeSeparator = ",";
            String claimSeparator = realmConfig.getUserStoreProperty(MULTI_ATTRIBUTE_SEPARATOR);
            if (claimSeparator != null && !claimSeparator.trim().isEmpty()) {
                userAttributeSeparator = claimSeparator;
            }
            String delim = "";
            StringBuffer roleBf = new StringBuffer();
            for (String role : roles) {
                roleBf.append(delim).append(role);
                delim = userAttributeSeparator;
            }
            finalValues.put(roleClaim, roleBf.toString());
        }

        return finalValues;
    }

    /**
     * @return
     */
    protected String getEveryOneRoleName() {
        return realmConfig.getEveryOneRoleName();
    }

    /**
     * @return
     */
    protected String getAdminRoleName() {
        return realmConfig.getAdminRoleName();
    }

    /**
     * @param credential
     * @return
     * @throws UserStoreException
     */
    protected boolean checkUserPasswordValid(Object credential) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{Object.class};
            Object object = callSecure("checkUserPasswordValid", new Object[]{credential}, argTypes);
            return (Boolean) object;
        }

        if (credential == null) {
            return false;
        }

        Secret credentialObj;
        try {
            credentialObj = Secret.getSecret(credential);
        } catch (UnsupportedSecretTypeException e) {
            throw new UserStoreException("Unsupported credential type", e);
        }

        try {
            if (credentialObj.getChars().length < 1) {
                return false;
            }

            String regularExpression =
                    realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_JAVA_REG_EX);
            return regularExpression == null || isFormatCorrect(regularExpression, credentialObj.getChars());
        } finally {
            credentialObj.clear();
        }

    }

    /**
     * @param userName
     * @return
     * @throws UserStoreException
     */
    protected boolean checkUserNameValid(String userName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class};
            Object object = callSecure("checkUserNameValid", new Object[]{userName}, argTypes);
            return (Boolean) object;
        }

        if (userName == null || CarbonConstants.REGISTRY_SYSTEM_USERNAME.equals(userName)) {
            return false;
        }

        userName = userName.trim();

        if (userName.length() < 1) {
            return false;
        }

        String regularExpression = realmConfig
                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_USER_NAME_JAVA_REG_EX);

        if (MultitenantUtils.isEmailUserName()) {
            regularExpression = realmConfig
                    .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_USER_NAME_WITH_EMAIL_JS_REG_EX);

            if (StringUtils.isEmpty(regularExpression) || StringUtils.isEmpty(regularExpression.trim())) {
                regularExpression = realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig
                        .PROPERTY_USER_NAME_JAVA_REG_EX);
            }
            if (StringUtils.isEmpty(regularExpression) || StringUtils.isEmpty(regularExpression.trim())) {
                regularExpression = UserCoreConstants.RealmConfig.EMAIL_VALIDATION_REGEX;
            }
        }

        if (regularExpression != null) {
            regularExpression = regularExpression.trim();
        }

        return regularExpression == null || regularExpression.equals("")
                || isFormatCorrect(regularExpression, userName);

    }

    /**
     * @param roleName
     * @return
     */
    protected boolean isRoleNameValid(String roleName) {
        if (roleName == null) {
            return false;
        }

        if (roleName.length() < 1) {
            return false;
        }

        String regularExpression = realmConfig
                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_ROLE_NAME_JAVA_REG_EX);
        if (regularExpression != null) {
            if (!isFormatCorrect(regularExpression, roleName)) {
                return false;
            }
        }

        return true;
    }

    /**
     * @param tenantID
     * @param userName
     * @return
     */
    protected String[] getRoleListOfUserFromCache(int tenantID, String userName) {
        if (userRolesCache != null) {
            String usernameWithDomain = UserCoreUtil.addDomainToName(userName, getMyDomainName());
            return userRolesCache.getRolesListOfUser(cacheIdentifier, tenantID, usernameWithDomain);
        }
        return null;
    }

    /**
     * @param tenantID
     */
    protected void clearUserRolesCacheByTenant(int tenantID) {
        if (userRolesCache != null) {
            userRolesCache.clearCacheByTenant(tenantID);
        }
        AuthorizationCache authorizationCache = AuthorizationCache.getInstance();
        authorizationCache.clearCacheByTenant(tenantID);
    }

    /**
     * @param userName
     */
    protected void clearUserRolesCache(String userName) {
        String usernameWithDomain = UserCoreUtil.addDomainToName(userName, getMyDomainName());
        if (userRolesCache != null) {
            userRolesCache.clearCacheEntry(cacheIdentifier, tenantId, usernameWithDomain);
        }
        AuthorizationCache authorizationCache = AuthorizationCache.getInstance();
        authorizationCache.clearCacheByUser(tenantId, usernameWithDomain);
    }

    /**
     * Add the role list to roles cache.
     * @param tenantID Tenant id.
     * @param userId User id.
     * @param roleList List of roles that are required to be added to the cache.
     */
    protected void addToUserRolesCache(int tenantID, String userId, String[] roleList) {
        if (userRolesCache != null) {
            String usernameWithDomain = UserCoreUtil.addDomainToName(userId, getMyDomainName());
            userRolesCache.addToCache(cacheIdentifier, tenantID, usernameWithDomain, roleList);
            AuthorizationCache authorizationCache = AuthorizationCache.getInstance();
            authorizationCache.clearCacheByTenant(tenantID);
        }
    }

    /**
     * @param regularExpression
     * @param attribute
     * @return
     */
    private boolean isFormatCorrect(String regularExpression, String attribute) {
        Pattern p2 = Pattern.compile(regularExpression);
        Matcher m2 = p2.matcher(attribute);
        return m2.matches();
    }

    private boolean isFormatCorrect(String regularExpression, char[] attribute) {

        boolean matches;
        CharBuffer charBuffer = CharBuffer.wrap(attribute);

        Pattern p2 = Pattern.compile(regularExpression);
        Matcher m2 = p2.matcher(charBuffer);
        matches = m2.matches();

        return matches;
    }

    /**
     * This is to replace escape characters in user name at user login if replace escape characters
     * enabled in user-mgt.xml. Some User Stores like ApacheDS stores user names by replacing escape
     * characters. In that case, we have to parse the username accordingly.
     *
     * @param userName
     */
    protected String replaceEscapeCharacters(String userName) {

        if (log.isDebugEnabled()) {
            log.debug("Replacing escape characters in " + userName);
        }
        String replaceEscapeCharactersAtUserLoginString = realmConfig
                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_REPLACE_ESCAPE_CHARACTERS_AT_USER_LOGIN);

        if (replaceEscapeCharactersAtUserLoginString != null) {
            replaceEscapeCharactersAtUserLogin = Boolean
                    .parseBoolean(replaceEscapeCharactersAtUserLoginString);
            if (log.isDebugEnabled()) {
                log.debug("Replace escape characters at userlogin is configured to: "
                        + replaceEscapeCharactersAtUserLoginString);
            }
            if (replaceEscapeCharactersAtUserLogin) {
                // Currently only '\' & '\\' are identified as escape characters
                // that needs to be
                // replaced.
                return userName.replaceAll("\\\\", "\\\\\\\\");
            }
        }
        return userName;
    }

    /**
     * TODO: Remove this method. We should not use DTOs
     *
     * @return
     * @throws UserStoreException
     */
    public RoleDTO[] getAllSecondaryRoleDTOs() throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{};
            Object object = callSecure("getAllSecondaryRoleDTOs", new Object[]{}, argTypes);
            return (RoleDTO[]) object;
        }

        UserStoreManager secondary = this.getSecondaryUserStoreManager();
        List<RoleDTO> roleList = new ArrayList<>();
        while (secondary != null) {
            String domain = secondary.getRealmConfiguration().getUserStoreProperty(
                    UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);
            String[] roles = secondary.getRoleNames(true);
            if (roles != null && roles.length > 0) {
                Collections.addAll(roleList, UserCoreUtil.convertRoleNamesToRoleDTO(roles, domain));
            }
            secondary = secondary.getSecondaryUserStoreManager();
        }
        return roleList.toArray(new RoleDTO[roleList.size()]);
    }

    /**
     * @param roleName
     * @param userList
     * @param permissions
     * @throws UserStoreException
     */
    public void addSystemRole(String roleName, String[] userList, Permission[] permissions)
            throws UserStoreException {

        if (!isRoleNameValid(roleName)) {
            String regEx = realmConfig
                    .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_ROLE_NAME_JAVA_REG_EX);
            throw new UserStoreException(
                    INVALID_ROLE + "Role name not valid. Role name must be a non null string with following format, "
                            + regEx);
        }

        if (systemUserRoleManager.isExistingRole(roleName)) {
            throw new UserStoreException("Role name: " + roleName
                    + " in the system. Please pick another role name.");
        }
        systemUserRoleManager.addSystemRole(roleName, userList);
    }


    /**
     * @param roleName
     * @param filter
     * @return
     * @throws UserStoreException
     */
    protected abstract String[] doGetUserListOfRole(String roleName, String filter)
            throws UserStoreException;

    /**
     * Get filtered list of roles for user.
     * @param username Username of the user.
     * @param filter Filter for the username.
     * @return List of roles for user.
     * @throws UserStoreException
     */
    @Deprecated
    public final String[] doGetRoleListOfUser(String username, String filter)
            throws UserStoreException {

        User user = new UserImpl();
        user.setUserName(username);
        return doGetRoleListOfUser(user, filter);
    }

    /**
     * Get filtered list of roles for user.
     * @param user   User object.
     * @param filter Filter for the username.
     * @return List of roles for user.
     * @throws UserStoreException User store level exception.
     */
    public final String[] doGetRoleListOfUser(User user, String filter)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{User.class, String.class};
            Object object = callSecure("doGetRoleListOfUser", new Object[]{user, filter}, argTypes);
            return (String[]) object;
        }

        String[] roleList;
        String[] internalRoles = doGetInternalRoleListOfUser(user, filter);
        String[] modifiedExternalRoleList = new String[0];

        if (readGroupsEnabled && doCheckExistingUser(user.getName())) {
            List<String> roles = new ArrayList<>();
            String[] externalRoles = doGetExternalRoleListOfUser(user.getName(), "*");
            roles.addAll(Arrays.asList(externalRoles));
            if (isSharedGroupEnabled()) {
                String[] sharedRoles = doGetSharedRoleListOfUser(user.getName(), null, "*");
                if (sharedRoles != null) {
                    roles.addAll(Arrays.asList(sharedRoles));
                }
            }
            modifiedExternalRoleList =
                    UserCoreUtil.addDomainToNames(roles.toArray(new String[roles.size()]),
                            getMyDomainName());
        }

        roleList = UserCoreUtil.combine(internalRoles, Arrays.asList(modifiedExternalRoleList));
        addToUserRolesCache(this.tenantId, user.getId(), roleList);

        return roleList;
    }

    /**
     * @param filter
     * @return
     * @throws UserStoreException
     */
    public final String[] getHybridRoles(String filter) throws UserStoreException {
        return hybridRoleManager.getHybridRoles(filter);
    }

    /**
     * @param claimList
     * @return
     * @throws UserStoreException
     */
    protected List<String> getMappingAttributeList(List<String> claimList)
            throws UserStoreException {

        ArrayList<String> attributeList;
        Iterator<String> claimIterator;

        attributeList = new ArrayList<>();
        if (claimList == null) {
            return attributeList;
        }
        claimIterator = claimList.iterator();
        while (claimIterator.hasNext()) {
            try {
                attributeList.add(claimManager.getAttributeName(claimIterator.next()));
            } catch (org.wso2.carbon.user.api.UserStoreException e) {
                throw new UserStoreException(e);
            }
        }
        return attributeList;
    }

    protected void doInitialSetup() throws UserStoreException {
        systemUserRoleManager = new SystemUserRoleManager(dataSource, tenantId);
        hybridRoleManager = new HybridRoleManager(dataSource, tenantId, realmConfig, userRealm);
        if (idManager == null) {
            idManager = new DefaultIdManager();
        }
    }

    /**
     * @return whether this is the initial startup
     * @throws UserStoreException
     */
    protected void doInitialUserAdding() throws UserStoreException {

        String systemUser = UserCoreUtil.removeDomainFromName(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME);
        String systemRole = UserCoreUtil.removeDomainFromName(CarbonConstants.REGISTRY_ANONNYMOUS_ROLE_NAME);

        if (!systemUserRoleManager.isExistingSystemUser(systemUser)) {
            systemUserRoleManager.addSystemUser(systemUser,
                    UserCoreUtil.getPolicyFriendlyRandomPassword(systemUser), new String[0]);
        }

        if (!systemUserRoleManager.isExistingRole(systemRole)) {
            systemUserRoleManager.addSystemRole(systemRole, new String[]{systemUser});
        }

        if (!hybridRoleManager.isExistingRole(UserCoreUtil.removeDomainFromName(realmConfig
                .getEveryOneRoleName()))) {
            hybridRoleManager.addHybridRole(
                    UserCoreUtil.removeDomainFromName(realmConfig.getEveryOneRoleName()), null);
        }
    }


    protected boolean isInitSetupDone() throws UserStoreException {

        boolean isInitialSetUp = false;
        String systemUser = UserCoreUtil.removeDomainFromName(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME);
        String systemRole = UserCoreUtil.removeDomainFromName(CarbonConstants.REGISTRY_ANONNYMOUS_ROLE_NAME);

        if (systemUserRoleManager.isExistingSystemUser(systemUser)) {
            isInitialSetUp = true;
        }

        if (systemUserRoleManager.isExistingRole(systemRole)) {
            isInitialSetUp = true;
        }

        return isInitialSetUp;
    }

    /**
     * @throws UserStoreException
     */
    protected void addInitialAdminData(boolean addAdmin, boolean initialSetup) throws UserStoreException {

        if (realmConfig.getAdminRoleName() == null || realmConfig.getAdminUserName() == null) {
            log.error("Admin user name or role name is not valid. Please provide valid values.");
            throw new UserStoreException(
                    "Admin user name or role name is not valid. Please provide valid values.");
        }
        String adminUserName = UserCoreUtil.removeDomainFromName(realmConfig.getAdminUserName());
        String adminRoleName = UserCoreUtil.removeDomainFromName(realmConfig.getAdminRoleName());
        boolean userExist = false;
        boolean roleExist = false;
        boolean isInternalRole = false;

        try {
            if (Boolean.parseBoolean(this.getRealmConfiguration().getUserStoreProperty(
                    UserCoreConstants.RealmConfig.READ_GROUPS_ENABLED))) {
                roleExist = doCheckExistingRole(adminRoleName);
            }
        } catch (Exception e) {
            //ignore
        }

        if (!roleExist) {
            try {
                roleExist = hybridRoleManager.isExistingRole(adminRoleName);
            } catch (Exception e) {
                //ignore
            }
            if (roleExist) {
                isInternalRole = true;
            }
        }

        try {
            userExist = doCheckExistingUser(adminUserName);
        } catch (Exception e) {
            //ignore
        }

        if (!userExist) {
            if (isReadOnly()) {
                String message = "Admin user can not be created in primary user store. " +
                        "User store is read only. " +
                        "Please pick a user name which is exist in the primary user store as Admin user";
                if (initialSetup) {
                    throw new UserStoreException(message);
                } else if (log.isDebugEnabled()) {
                    log.error(message);
                }
            } else if (addAdmin) {
                try {
                    this.doAddUser(adminUserName, realmConfig.getAdminPassword(),
                            null, null, null, false);
                } catch (Exception e) {
                    String message = "Admin user has not been created. " +
                            "Error occurs while creating Admin user in primary user store.";
                    if (initialSetup) {
                        throw new UserStoreException(message, e);
                    } else if (log.isDebugEnabled()) {
                        log.error(message, e);
                    }
                }
            } else {
                if (initialSetup) {
                    String message = "Admin user can not be created in primary user store. Add-Admin has been " +
                            "set to false. Please pick a User name which is exist in the primary user store " +
                            "as Admin user";
                    log.error(message);
                    throw new UserStoreException(message);
                }
            }
        }


        if (!roleExist) {
            if (addAdmin) {
                if (!isReadOnly() && writeGroupsEnabled) {
                    try {
                        this.doAddRole(adminRoleName, new String[]{adminUserName}, false);
                    } catch (org.wso2.carbon.user.api.UserStoreException e) {
                        String message = "Admin role has not been created. " +
                                "Error occurs while creating Admin role in primary user store.";
                        if (initialSetup) {
                            throw new UserStoreException(message, e);
                        } else if (log.isDebugEnabled()) {
                            log.error(message, e);
                        }
                    }
                } else {
                    // creates internal role
                    try {
                        hybridRoleManager.addHybridRole(adminRoleName, new String[]{adminUserName});
                        isInternalRole = true;
                    } catch (Exception e) {
                        String message = "Admin role has not been created. " +
                                "Error occurs while creating Admin role in primary user store.";
                        if (initialSetup) {
                            throw new UserStoreException(message, e);
                        } else if (log.isDebugEnabled()) {
                            log.error(message, e);
                        }
                    }
                }
            } else {
                String message = "Admin role can not be created in primary user store. " +
                        "Add-Admin has been set to false. " +
                        "Please pick a Role name which is exist in the primary user store as Admin Role";
                if (initialSetup) {
                    throw new UserStoreException(message);
                } else if (log.isDebugEnabled()) {
                    log.error(message);
                }
            }
        }


        if (isInternalRole) {
            if (!hybridRoleManager.isUserInRole(adminUserName, adminRoleName)) {
                try {
                    hybridRoleManager.updateHybridRoleListOfUser(adminUserName, null,
                            new String[]{adminRoleName});
                } catch (Exception e) {
                    String message = "Admin user has not been assigned to Admin role. " +
                            "Error while assignment is done";
                    if (initialSetup) {
                        throw new UserStoreException(message, e);
                    } else if (log.isDebugEnabled()) {
                        log.error(message, e);
                    }
                }
            }
            realmConfig.setAdminRoleName(UserCoreUtil.addInternalDomainName(adminRoleName));
        } else if (!isReadOnly() && writeGroupsEnabled) {
            if (!this.doCheckIsUserInRole(adminUserName, adminRoleName)) {
                if (addAdmin) {
                    try {
                        this.doUpdateRoleListOfUser(adminUserName, null,
                                new String[]{adminRoleName});
                    } catch (Exception e) {
                        String message = "Admin user has not been assigned to Admin role. " +
                                "Error while assignment is done";
                        if (initialSetup) {
                            throw new UserStoreException(message, e);
                        } else if (log.isDebugEnabled()) {
                            log.error(message, e);
                        }
                    }
                } else {
                    String message = "Admin user can not be assigned to Admin role " +
                            "Add-Admin has been set to false. Please do the assign it in user store level";
                    if (initialSetup) {
                        throw new UserStoreException(message);
                    } else if (log.isDebugEnabled()) {
                        log.error(message);
                    }
                }
            }
        }

        doInitialUserAdding();
    }

    /**
     * @param type
     * @return
     * @throws UserStoreException
     */
    public Map<String, Integer> getMaxListCount(String type) throws UserStoreException {

        if (!type.equals(UserCoreConstants.RealmConfig.PROPERTY_MAX_USER_LIST)
                && !type.equals(UserCoreConstants.RealmConfig.PROPERTY_MAX_ROLE_LIST)) {
            throw new UserStoreException("Invalid count parameter");
        }

        if (type.equals(UserCoreConstants.RealmConfig.PROPERTY_MAX_USER_LIST)
                && maxUserListCount != null) {
            return maxUserListCount;
        }

        if (type.equals(UserCoreConstants.RealmConfig.PROPERTY_MAX_ROLE_LIST)
                && maxRoleListCount != null) {
            return maxRoleListCount;
        }

        Map<String, Integer> maxListCount = new HashMap<>();
        for (Map.Entry<String, UserStoreManager> entry : userStoreManagerHolder.entrySet()) {
            UserStoreManager storeManager = entry.getValue();
            String maxConfig = storeManager.getRealmConfiguration().getUserStoreProperty(type);

            if (maxConfig == null) {
                // set a default value
                maxConfig = MAX_LIST_LENGTH;
            }
            maxListCount.put(entry.getKey(), Integer.parseInt(maxConfig));
        }

        if (realmConfig.getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME) == null) {
            String maxConfig = realmConfig.getUserStoreProperty(type);
            if (maxConfig == null) {
                // set a default value
                maxConfig = MAX_LIST_LENGTH;
            }
            maxListCount.put(null, Integer.parseInt(maxConfig));
        }

        if (type.equals(UserCoreConstants.RealmConfig.PROPERTY_MAX_USER_LIST)) {
            this.maxUserListCount = maxListCount;
            return this.maxUserListCount;
        } else if (type.equals(UserCoreConstants.RealmConfig.PROPERTY_MAX_ROLE_LIST)) {
            this.maxRoleListCount = maxListCount;
            return this.maxRoleListCount;
        } else {
            throw new UserStoreException("Invalid count parameter");
        }
    }

    /**
     * @return
     */
    protected String getMyDomainName() {
        return UserCoreUtil.getDomainName(realmConfig);
    }

    protected void persistDomain() throws UserStoreException {
        String domain = UserCoreUtil.getDomainName(this.realmConfig);
        if (domain != null) {
            UserCoreUtil.persistDomain(domain, this.tenantId, this.dataSource);
        }
    }

    public void deletePersistedDomain(String domain) throws UserStoreException {
        if (domain != null) {
            if (log.isDebugEnabled()) {
                log.debug("Deleting persisted domain " + domain);
            }
            UserCoreUtil.deletePersistedDomain(domain, this.tenantId, this.dataSource);
        }
    }

    public void updatePersistedDomain(String oldDomain, String newDomain) throws UserStoreException {
        if (oldDomain != null && newDomain != null) {
            // Checks for the newDomain exists already
            // Traverse through realm configuration chain since USM chain doesn't contains the disabled USMs
            RealmConfiguration realmConfigTmp = this.getRealmConfiguration();
            while (realmConfigTmp != null) {
                String domainName = realmConfigTmp.getUserStoreProperty(UserCoreConstants.RealmConfig
                        .PROPERTY_DOMAIN_NAME);
                if (newDomain.equalsIgnoreCase(domainName)) {
                    throw new UserStoreException("Cannot update persisted domain name " + oldDomain + " into "
                            + newDomain + ". New domain name already in use");
                }
                realmConfigTmp = realmConfigTmp.getSecondaryRealmConfig();
            }

            if (log.isDebugEnabled()) {
                log.debug("Renaming persisted domain " + oldDomain + " to " + newDomain);
            }
            UserCoreUtil.updatePersistedDomain(oldDomain, newDomain, this.tenantId, this.dataSource);

        }
    }

    /**
     * Checks whether the role is a shared role or not
     *
     * @param roleName
     * @param roleNameBase
     * @return
     */
    public boolean isSharedRole(String roleName, String roleNameBase) {

        // Only checks the shared groups are enabled
        return isSharedGroupEnabled();
    }

    /**
     * Checks whether the provided role name belongs to the logged in tenant.
     * This check is done using the domain name which is appended at the end of
     * the role name
     *
     * @param roleName
     * @return
     */
    protected boolean isOwnRole(String roleName) {
        return true;
    }

    @Override
    public void addRole(String roleName, String[] userList,
                        org.wso2.carbon.user.api.Permission[] permissions)
            throws org.wso2.carbon.user.api.UserStoreException {
        addRole(roleName, userList, permissions, false);

    }

    public boolean isOthersSharedRole(String roleName) {
        return false;
    }

    public void notifyListeners(String domainName) {
        for (UserStoreManagerConfigurationListener aListener : listener) {
            aListener.propertyChange(domainName);
        }
    }

    public void addChangeListener(UserStoreManagerConfigurationListener newListener) {
        listener.add(newListener);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private UserStoreManager createSecondaryUserStoreManager(RealmConfiguration realmConfig,
                                                             UserRealm realm) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{RealmConfiguration.class, UserRealm.class};
            Object object = callSecure("createSecondaryUserStoreManager", new Object[]{realmConfig, realm}, argTypes);
            return (UserStoreManager) object;
        }

        // setting global realm configurations such as everyone role, admin role and admin user
        realmConfig.setEveryOneRoleName(this.realmConfig.getEveryOneRoleName());
        realmConfig.setAdminUserName(this.realmConfig.getAdminUserName());
        realmConfig.setAdminRoleName(this.realmConfig.getAdminRoleName());

        String className = realmConfig.getUserStoreClass();
        if (className == null) {
            String errmsg = "Unable to add user store. UserStoreManager class name is null.";
            log.error(errmsg);
            throw new UserStoreException(errmsg);
        }

        HashMap<String, Object> properties = new HashMap<String, Object>();
        properties.put(UserCoreConstants.DATA_SOURCE, this.dataSource);
        properties.put(UserCoreConstants.FIRST_STARTUP_CHECK, false);

        Class[] initClassOpt1 = new Class[]{RealmConfiguration.class, Map.class,
                ClaimManager.class, ProfileConfigurationManager.class, UserRealm.class,
                Integer.class};
        Object[] initObjOpt1 = new Object[]{realmConfig, properties, realm.getClaimManager(), null, realm,
                tenantId};

        // These two methods won't be used
        Class[] initClassOpt2 = new Class[]{RealmConfiguration.class, Map.class,
                ClaimManager.class, ProfileConfigurationManager.class, UserRealm.class};
        Object[] initObjOpt2 = new Object[]{realmConfig, properties, realm.getClaimManager(), null, realm};

        Class[] initClassOpt3 = new Class[]{RealmConfiguration.class, Map.class};
        Object[] initObjOpt3 = new Object[]{realmConfig, properties};

        try {
            Class clazz = Class.forName(className);
            Constructor constructor = null;
            Object newObject = null;

            if (log.isDebugEnabled()) {
                log.debug("Start initializing class with the first option");
            }

            try {
                constructor = clazz.getConstructor(initClassOpt1);
                newObject = constructor.newInstance(initObjOpt1);
                return (UserStoreManager) newObject;
            } catch (NoSuchMethodException e) {
                // if not found try again.
                if (log.isDebugEnabled()) {
                    log.debug("Cannont initialize " + className + " using the option 1");
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("End initializing class with the first option");
            }

            try {
                constructor = clazz.getConstructor(initClassOpt2);
                newObject = constructor.newInstance(initObjOpt2);
                return (UserStoreManager) newObject;
            } catch (NoSuchMethodException e) {
                // if not found try again.
                if (log.isDebugEnabled()) {
                    log.debug("Cannot initialize " + className + " using the option 2");
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("End initializing class with the second option");
            }

            try {
                constructor = clazz.getConstructor(initClassOpt3);
                newObject = constructor.newInstance(initObjOpt3);
                return (UserStoreManager) newObject;
            } catch (NoSuchMethodException e) {
                // cannot initialize in any of the methods. Throw exception.
                String message = "Cannot initialize " + className + ". Error " + e.getMessage();
                log.error(message);
                throw new UserStoreException(message);
            }

        } catch (Throwable e) {
            log.error("Cannot create " + className, e);
            throw new UserStoreException(e.getMessage() + "Type " + e.getClass(), e);
        }

    }

    /**
     * Adding new User Store Manager to USM chain
     *
     * @param userStoreRealmConfig
     * @param realm
     * @throws UserStoreException
     */
    public void addSecondaryUserStoreManager(RealmConfiguration userStoreRealmConfig,
                                             UserRealm realm) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{RealmConfiguration.class, UserRealm.class};
            callSecure("addSecondaryUserStoreManager", new Object[]{userStoreRealmConfig, realm}, argTypes);
            return;
        }

        boolean isDisabled = Boolean.parseBoolean(userStoreRealmConfig
                .getUserStoreProperty(UserCoreConstants.RealmConfig.USER_STORE_DISABLED));

        String domainName = userStoreRealmConfig
                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);

        if (isDisabled) {
            log.warn("Secondary user store disabled with domain " + domainName + ".");
        } else {
            // Creating new UserStoreManager
            UserStoreManager manager = createSecondaryUserStoreManager(userStoreRealmConfig, realm);

            if (domainName != null) {
                if (this.getSecondaryUserStoreManager(domainName) != null) {
                    String errorMessage = "Could not initialize new user store manager : " + domainName
                            + " Duplicate domain names not allowed.";
                    if (log.isDebugEnabled()) {
                        log.debug(errorMessage);
                    }
                    throw new UserStoreException(errorMessage);
                } else {
                    // Fulfilled requirements for adding UserStore,

                    // Now adding UserStoreManager to end of the UserStoreManager chain
                    UserStoreManager tmpUserStoreManager = this;
                    while (tmpUserStoreManager.getSecondaryUserStoreManager() != null) {
                        tmpUserStoreManager = tmpUserStoreManager.getSecondaryUserStoreManager();
                    }
                    tmpUserStoreManager.setSecondaryUserStoreManager(manager);

                    // update domainName-USM map to retrieve USM directly by its domain name
                    this.addSecondaryUserStoreManager(domainName.toUpperCase(), tmpUserStoreManager
                            .getSecondaryUserStoreManager());

                    if (log.isDebugEnabled()) {
                        log.debug("UserStoreManager : " + domainName + "added to the list");
                    }
                }
            } else {
                log.warn("Could not initialize new user store manager.  "
                        + "Domain name is not defined");
            }
        }
    }

    /**
     * Remove given User Store Manager from USM chain
     *
     * @param userStoreDomainName
     * @throws UserStoreException
     */
    public void removeSecondaryUserStoreManager(String userStoreDomainName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class};
            callSecure("removeSecondaryUserStoreManager", new Object[]{userStoreDomainName}, argTypes);
            return;
        }

        if (StringUtils.isEmpty(userStoreDomainName)) {
            throw new UserStoreException("Cannot remove user store. User store domain name is null or empty.");
        }

        userStoreDomainName = userStoreDomainName.toUpperCase();

        boolean isUSMContainsInMap = false;
        if (this.userStoreManagerHolder.containsKey(userStoreDomainName.toUpperCase())) {
            isUSMContainsInMap = true;
            this.userStoreManagerHolder.remove(userStoreDomainName.toUpperCase());
            if (log.isDebugEnabled()) {
                log.debug("UserStore: " + userStoreDomainName + " removed from map");
            }
        }

        boolean isUSMContainInChain = false;
        UserStoreManager prevUserStoreManager = this;
        while (prevUserStoreManager.getSecondaryUserStoreManager() != null) {
            UserStoreManager secondaryUSM = prevUserStoreManager.getSecondaryUserStoreManager();
            if (secondaryUSM.getRealmConfiguration().getUserStoreProperty(UserStoreConfigConstants.DOMAIN_NAME)
                    .equalsIgnoreCase(userStoreDomainName)) {
                isUSMContainInChain = true;

                // Omit deleting user store manager from the chain
                prevUserStoreManager.setSecondaryUserStoreManager(secondaryUSM.getSecondaryUserStoreManager());
                log.info("User store: " + userStoreDomainName + " of tenant:" + tenantId
                        + " is removed from user store chain.");
                break;
            }
            prevUserStoreManager = secondaryUSM;
        }

        if (!isUSMContainsInMap && isUSMContainInChain) {
            throw new UserStoreException("Removed user store manager : " + userStoreDomainName +
                    " didn't exists in userStoreManagerHolder map");
        } else if (isUSMContainsInMap && !isUSMContainInChain) {
            throw new UserStoreException("Removed user store manager : " + userStoreDomainName +
                    " didn't exists in user store manager chain");
        }
    }

    public HybridRoleManager getInternalRoleManager() {
        return hybridRoleManager;
    }

    /**
     * Set the id manager that is used for this instance.
     * @param idManager Instance of the id manager.
     */
    public void setIdManager(IdManager idManager) {
        this.idManager = idManager;
    }

    /**
     * Get the id manager that is used for this instance.
     * @return IdManager instance.
     */
    protected IdManager getIdManager() {
        return idManager;
    }
}