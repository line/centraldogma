/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.centraldogma.server.internal.admin.authentication;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.cache.MemoryConstrainedCacheManager;
import org.apache.shiro.config.Ini;
import org.apache.shiro.config.IniSecurityManagerFactory;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.DefaultSessionManager;
import org.apache.shiro.session.mgt.SessionContext;
import org.apache.shiro.session.mgt.SessionKey;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.SubjectContext;
import org.apache.shiro.util.Factory;

import com.linecorp.centraldogma.server.support.shiro.FileBasedSessionDAO;

public final class CentralDogmaSecurityManager implements SecurityManager {

    private final FileBasedSessionDAO sessionDao;
    private final CentralDogmaSessionManager sessionManager;
    private final SecurityManager delegate;

    public CentralDogmaSecurityManager(File dataDir, Ini securityConfig,
                                       long sessionTimeoutMillis) {
        try {
            sessionDao = new FileBasedSessionDAO(new File(dataDir, "_sessions").toPath());
        } catch (IOException e) {
            throw new IOError(e);
        }

        checkArgument(sessionTimeoutMillis > 0,
                      "sessionTimeoutMillis: %s (expected: > 0)", sessionTimeoutMillis);
        sessionManager = new CentralDogmaSessionManager(sessionDao, sessionTimeoutMillis);
        final Factory<SecurityManager> factory = new IniSecurityManagerFactory(securityConfig) {
            @Override
            protected SecurityManager createDefaultInstance() {
                DefaultSecurityManager securityManager = new DefaultSecurityManager();
                securityManager.setSessionManager(sessionManager);
                securityManager.setCacheManager(new MemoryConstrainedCacheManager());
                return securityManager;
            }
        };
        delegate = factory.getInstance();
    }

    public void enableSessionValidation() {
        sessionManager.setSessionValidationSchedulerEnabled(true);
        sessionManager.enableSessionValidation();
    }

    public void disableSessionValidation() {
        sessionManager.setSessionValidationSchedulerEnabled(false);
        sessionManager.disableSessionValidation();
    }

    public boolean sessionExists(String sessionId) {
        return sessionDao.exists(sessionId);
    }

    public SimpleSession getSerializableSession(String sessionId) {
        return sessionDao.readSession(sessionId);
    }

    public void createSession(SimpleSession session) {
        sessionDao.create(session);
    }

    public void removeSession(String sessionId) {
        sessionDao.delete(sessionId);
    }
    // SecurityManager methods

    @Override
    public Subject login(Subject subject, AuthenticationToken authenticationToken) {
        return delegate.login(subject, authenticationToken);
    }

    @Override
    public void logout(Subject subject) {
        delegate.logout(subject);
    }

    @Override
    public Subject createSubject(SubjectContext context) {
        return delegate.createSubject(context);
    }

    // SessionManager methods

    @Override
    public Session start(SessionContext context) {
        return sessionManager.start(context);
    }

    @Override
    public Session getSession(SessionKey key) {
        return sessionManager.getSession(key);
    }

    // Authenticator methods

    @Override
    public AuthenticationInfo authenticate(AuthenticationToken authenticationToken) {
        return delegate.authenticate(authenticationToken);
    }

    // Authorizer methods
    @Override
    public boolean isPermitted(PrincipalCollection principals, String permission) {
        return delegate.isPermitted(principals, permission);
    }

    @Override
    public boolean isPermitted(PrincipalCollection subjectPrincipal,
                               Permission permission) {
        return delegate.isPermitted(subjectPrincipal, permission);
    }

    @Override
    public boolean[] isPermitted(PrincipalCollection subjectPrincipal,
                                 String... permissions) {
        return delegate.isPermitted(subjectPrincipal, permissions);
    }

    @Override
    public boolean[] isPermitted(PrincipalCollection subjectPrincipal,
                                 List<Permission> permissions) {
        return delegate.isPermitted(subjectPrincipal, permissions);
    }

    @Override
    public boolean isPermittedAll(PrincipalCollection subjectPrincipal,
                                  String... permissions) {
        return delegate.isPermittedAll(subjectPrincipal, permissions);
    }

    @Override
    public boolean isPermittedAll(PrincipalCollection subjectPrincipal,
                                  Collection<Permission> permissions) {
        return delegate.isPermittedAll(subjectPrincipal, permissions);
    }

    @Override
    public void checkPermission(PrincipalCollection subjectPrincipal, String permission) {
        delegate.checkPermission(subjectPrincipal, permission);
    }

    @Override
    public void checkPermission(PrincipalCollection subjectPrincipal, Permission permission) {
        delegate.checkPermission(subjectPrincipal, permission);
    }

    @Override
    public void checkPermissions(PrincipalCollection subjectPrincipal, String... permissions) {
        delegate.checkPermissions(subjectPrincipal, permissions);
    }

    @Override
    public void checkPermissions(PrincipalCollection subjectPrincipal, Collection<Permission> permissions) {
        delegate.checkPermissions(subjectPrincipal, permissions);
    }

    @Override
    public boolean hasRole(PrincipalCollection subjectPrincipal, String roleIdentifier) {
        return delegate.hasRole(subjectPrincipal, roleIdentifier);
    }

    @Override
    public boolean[] hasRoles(PrincipalCollection subjectPrincipal, List<String> roleIdentifiers) {
        return delegate.hasRoles(subjectPrincipal, roleIdentifiers);
    }

    @Override
    public boolean hasAllRoles(PrincipalCollection subjectPrincipal, Collection<String> roleIdentifiers) {
        return delegate.hasAllRoles(subjectPrincipal, roleIdentifiers);
    }

    @Override
    public void checkRole(PrincipalCollection subjectPrincipal, String roleIdentifier) {
        delegate.checkRole(subjectPrincipal, roleIdentifier);
    }

    @Override
    public void checkRoles(PrincipalCollection subjectPrincipal, Collection<String> roleIdentifiers) {
        delegate.checkRoles(subjectPrincipal, roleIdentifiers);
    }

    @Override
    public void checkRoles(PrincipalCollection subjectPrincipal, String... roleIdentifiers) {
        delegate.checkRoles(subjectPrincipal, roleIdentifiers);
    }

    private static final class CentralDogmaSessionManager extends DefaultSessionManager {

        CentralDogmaSessionManager(SessionDAO sessionDao, long sessionTimeoutMillis) {
            setSessionDAO(sessionDao);
            // Validating all active sessions for every hour.
            setSessionValidationInterval(Duration.ofHours(1).toMillis());
            setGlobalSessionTimeout(sessionTimeoutMillis);
        }

        @Override
        protected synchronized void enableSessionValidation() {
            super.enableSessionValidation();
        }

        @Override
        protected synchronized void disableSessionValidation() {
            super.disableSessionValidation();
        }
    }
}
