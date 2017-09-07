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

package com.linecorp.centraldogma.server.support.shiro;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.regex.Pattern;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapContext;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.realm.activedirectory.ActiveDirectoryRealm;
import org.apache.shiro.realm.ldap.LdapContextFactory;
import org.apache.shiro.realm.ldap.LdapUtils;

/**
 * A realm for Apache Shiro. This realm binds to the active directory LDAP server first with
 * the system username and password, then searches a distinguished name(DN) of a user. After that,
 * it binds to the active directory LDAP server again with the searched DN and the user password
 * to authenticate the user. The INI configuration might be specified as follows:
 *
 * <p>
 * [main]
 * adRealm = com.linecorp.centraldogma.server.support.shiro.SearchFirstActiveDirectoryRealm
 * adRealm.url = ldap://hostname:port
 * adRealm.systemUsername = admin
 * adRealm.systemPassword = admin
 * adRealm.searchBase = ...
 * adRealm.searchFilter = cn={0}
 * adRealm.searchTimeoutMillis = 10000
 */
public class SearchFirstActiveDirectoryRealm extends ActiveDirectoryRealm {

    private static final Pattern USERNAME_PLACEHOLDER = Pattern.compile("\\{0\\}");
    private static final String DEFAULT_SEARCH_FILTER = "cn={0}";
    private static int DEFAULT_SEARCH_TIMEOUT_MILLIS = (int) Duration.ofSeconds(10).toMillis();

    private String searchFilter = DEFAULT_SEARCH_FILTER;
    private int searchTimeoutMillis = DEFAULT_SEARCH_TIMEOUT_MILLIS;

    /**
     * Returns a search filter string.
     */
    protected String getSearchFilter() {
        return searchFilter;
    }

    /**
     * Sets a search filter string.
     */
    protected void setSearchFilter(String searchFilter) {
        this.searchFilter = requireNonNull(searchFilter, "searchFilter");
    }

    /**
     * Returns a timeout(ms) for LDAP search.
     */
    public int getSearchTimeoutMillis() {
        return searchTimeoutMillis;
    }

    /**
     * Sets a timeout(ms) for LDAP search.
     */
    protected void setSearchTimeoutMillis(int searchTimeoutMillis) {
        checkArgument(searchTimeoutMillis >= 0,
                      "searchTimeoutMillis should be 0 or positive number");
        this.searchTimeoutMillis = searchTimeoutMillis;
    }

    /**
     * Builds an {@link AuthenticationInfo} object by querying the active directory LDAP context for the
     * specified username.
     */
    @Override
    protected AuthenticationInfo queryForAuthenticationInfo(
            AuthenticationToken token, LdapContextFactory ldapContextFactory) throws NamingException {

        final UsernamePasswordToken upToken = ensureUsernamePasswordToken(token);
        final String userDn = findUserDn(ldapContextFactory, upToken.getUsername());

        LdapContext ctx = null;
        try {
            // Binds using the username and password provided by the user.
            ctx = ldapContextFactory.getLdapContext(userDn, upToken.getPassword());
        } finally {
            LdapUtils.closeContext(ctx);
        }
        return buildAuthenticationInfo(upToken.getUsername(), upToken.getPassword());
    }

    /**
     * Finds a distinguished name(DN) of a user by querying the active directory LDAP context for the
     * specified username.
     */
    protected String findUserDn(LdapContextFactory ldapContextFactory, String username) throws NamingException {
        LdapContext ctx = null;
        try {
            // Binds using the system username and password.
            ctx = ldapContextFactory.getSystemLdapContext();

            final SearchControls ctrl = new SearchControls();
            ctrl.setCountLimit(1);
            ctrl.setSearchScope(SearchControls.SUBTREE_SCOPE);
            ctrl.setTimeLimit(searchTimeoutMillis);

            final String filter =
                    searchFilter != null ? USERNAME_PLACEHOLDER.matcher(searchFilter)
                                                               .replaceAll(username)
                                         : username;
            final NamingEnumeration<SearchResult> result = ctx.search(searchBase, filter, ctrl);
            try {
                if (!result.hasMore()) {
                    throw new AuthenticationException("No username: " + username);
                }
                return result.next().getNameInNamespace();
            } finally {
                result.close();
            }
        } finally {
            LdapUtils.closeContext(ctx);
        }
    }

    private static UsernamePasswordToken ensureUsernamePasswordToken(AuthenticationToken token) {
        if (token instanceof UsernamePasswordToken) {
            return (UsernamePasswordToken) token;
        }

        throw new IllegalArgumentException("Token '" + token.getClass().getName() + "' is not supported.");
    }
}

