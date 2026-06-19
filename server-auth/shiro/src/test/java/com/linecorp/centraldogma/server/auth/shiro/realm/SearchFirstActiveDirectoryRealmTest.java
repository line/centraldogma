/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.auth.shiro.realm;

import static com.linecorp.centraldogma.server.auth.shiro.realm.SearchFirstActiveDirectoryRealm.encodeLdapFilter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapContext;

import org.apache.shiro.realm.ldap.LdapContextFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SearchFirstActiveDirectoryRealmTest {

    // ===========================================
    // encodeLdapFilter() unit tests
    // ===========================================

    @Test
    void normalUsernameIsNotChanged() {
        assertThat(encodeLdapFilter("alice")).isEqualTo("alice");
        assertThat(encodeLdapFilter("bob.smith")).isEqualTo("bob.smith");
        assertThat(encodeLdapFilter("user123")).isEqualTo("user123");
    }

    @Test
    void wildcardIsEscaped() {
        assertThat(encodeLdapFilter("*")).isEqualTo("\\2a");
        assertThat(encodeLdapFilter("a*")).isEqualTo("a\\2a");
        assertThat(encodeLdapFilter("**")).isEqualTo("\\2a\\2a");
    }

    @Test
    void parenthesesAreEscaped() {
        assertThat(encodeLdapFilter("alice)(uid=*"))
                .isEqualTo("alice\\29\\28uid=\\2a");
        assertThat(encodeLdapFilter("(")).isEqualTo("\\28");
        assertThat(encodeLdapFilter(")")).isEqualTo("\\29");
    }

    @Test
    void backslashIsEscaped() {
        assertThat(encodeLdapFilter("a\\b")).isEqualTo("a\\5cb");
        assertThat(encodeLdapFilter("\\")).isEqualTo("\\5c");
    }

    @Test
    void nullCharacterIsEscaped() {
        assertThat(encodeLdapFilter("a\0b")).isEqualTo("a\\00b");
    }

    @Test
    void nullInputReturnsEmpty() {
        assertThat(encodeLdapFilter(null)).isEmpty();
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(encodeLdapFilter("")).isEmpty();
    }

    @Test
    void complexInjectionPayloadIsFullyEscaped() {
        // Group-membership injection payload
        assertThat(encodeLdapFilter("*)(memberOf=CN=Domain Admins"))
                .isEqualTo("\\2a\\29\\28memberOf=CN=Domain Admins");

        // AND-injection payload
        assertThat(encodeLdapFilter("x)(|(cn=alice)(cn=admin"))
                .isEqualTo("x\\29\\28|\\28cn=alice\\29\\28cn=admin");
    }

    @Test
    void setSearchFilter_rejectsFilterWithoutPlaceholder() {
        final SearchFirstActiveDirectoryRealm realm = new SearchFirstActiveDirectoryRealm();
        assertThatThrownBy(() -> realm.setSearchFilter("cn=admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{0}");
    }

    @Test
    void nonAsciiCharactersArePassedThrough() {
        // Korean
        assertThat(encodeLdapFilter("홍길동")).isEqualTo("홍길동");
        // Japanese
        assertThat(encodeLdapFilter("田中太郎")).isEqualTo("田中太郎");
        // Mixed with special chars
        assertThat(encodeLdapFilter("홍길동*")).isEqualTo("홍길동\\2a");
    }

    @Test
    void allMetacharactersCombined() {
        assertThat(encodeLdapFilter("\\*(\0)"))
                .isEqualTo("\\5c\\2a\\28\\00\\29");
    }

    // ===========================================
    // findUserDn() integration tests with mock
    // ===========================================

    @Test
    void findUserDn_normalUsername() throws Exception {
        assertFilterSentToLdap("cn={0}", "alice", "cn=alice");
    }

    @Test
    void findUserDn_wildcardIsEscaped() throws Exception {
        assertFilterSentToLdap("cn={0}", "*", "cn=\\2a");
    }

    @Test
    void findUserDn_parenthesisInjectionIsEscaped() throws Exception {
        assertFilterSentToLdap("cn={0}", "alice)(uid=*", "cn=alice\\29\\28uid=\\2a");
    }

    @Test
    void findUserDn_andInjectionIsEscaped() throws Exception {
        assertFilterSentToLdap("cn={0}", "x)(|(cn=alice)(cn=admin",
                               "cn=x\\29\\28|\\28cn=alice\\29\\28cn=admin");
    }

    @Test
    void findUserDn_groupMembershipInjectionIsEscaped() throws Exception {
        assertFilterSentToLdap(
                "(&(objectClass=user)(sAMAccountName={0}))",
                "*)(memberOf=CN=Domain Admins,CN=Users,DC=corp",
                "(&(objectClass=user)(sAMAccountName=\\2a\\29\\28memberOf=CN=Domain Admins,CN=Users,DC=corp))");
    }

    @Test
    void findUserDn_customAdFilterWithMultiplePlaceholders() throws Exception {
        assertFilterSentToLdap(
                "(&(cn={0})(uid={0}))",
                "alice*",
                "(&(cn=alice\\2a)(uid=alice\\2a))");
    }

    @Test
    void findUserDn_customSamAccountNameFilter() throws Exception {
        assertFilterSentToLdap(
                "(&(objectClass=user)(sAMAccountName={0}))",
                "bob",
                "(&(objectClass=user)(sAMAccountName=bob))");
    }

    @Test
    void findUserDn_usernameContainingPlaceholderLiteral() throws Exception {
        // Username is literally "{0}" — not a special char for LDAP, so passes through.
        // String.replace substitutes the placeholder with the escaped value "{0}".
        assertFilterSentToLdap("cn={0}", "{0}", "cn={0}");
    }

    @Test
    void findUserDn_returnsNullWhenNoResults() throws Exception {
        final SearchFirstActiveDirectoryRealm realm = createRealm("cn={0}");
        final LdapContextFactory factory = mockFactory(false);

        assertThat(realm.findUserDn(factory, "nonexistent")).isNull();
    }

    @Test
    void findUserDn_returnsDnWhenFound() throws Exception {
        final SearchFirstActiveDirectoryRealm realm = createRealm("cn={0}");
        final LdapContextFactory factory = mockFactory(true);

        assertThat(realm.findUserDn(factory, "alice"))
                .isEqualTo("cn=alice,dc=example,dc=com");
    }

    // ===========================================
    // Helpers
    // ===========================================

    private static void assertFilterSentToLdap(String searchFilter, String username,
                                               String expectedFilter) throws Exception {
        final SearchFirstActiveDirectoryRealm realm = createRealm(searchFilter);
        final LdapContextFactory factory = mockFactory(false);

        realm.findUserDn(factory, username);

        final ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        final LdapContext ctx = factory.getSystemLdapContext();
        verify(ctx).search(anyString(), filterCaptor.capture(), any(SearchControls.class));
        assertThat(filterCaptor.getValue()).isEqualTo(expectedFilter);
    }

    private static SearchFirstActiveDirectoryRealm createRealm(String searchFilter) {
        final SearchFirstActiveDirectoryRealm realm = new SearchFirstActiveDirectoryRealm();
        realm.setSearchBase("dc=example,dc=com");
        realm.setSearchFilter(searchFilter);
        return realm;
    }

    @SuppressWarnings("unchecked")
    private static LdapContextFactory mockFactory(boolean hasResult) throws NamingException {
        final LdapContext ctx = mock(LdapContext.class);
        final NamingEnumeration<SearchResult> results = mock(NamingEnumeration.class);
        when(results.hasMore()).thenReturn(hasResult);

        if (hasResult) {
            final SearchResult searchResult = mock(SearchResult.class);
            when(searchResult.getNameInNamespace()).thenReturn("cn=alice,dc=example,dc=com");
            when(results.next()).thenReturn(searchResult);
        }

        when(ctx.search(anyString(), anyString(), any(SearchControls.class))).thenReturn(results);

        final LdapContextFactory factory = mock(LdapContextFactory.class);
        when(factory.getSystemLdapContext()).thenReturn(ctx);
        return factory;
    }
}
