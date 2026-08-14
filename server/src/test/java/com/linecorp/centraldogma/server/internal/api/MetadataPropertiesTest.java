/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.api;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getSessionCookie;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MetadataPropertiesConfig;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MetadataPropertiesTest {

    private static final String PROJECT_SCHEMA =
            '{' +
            "  \"type\": \"object\"," +
            "  \"properties\": {" +
            "    \"serviceId\": { \"type\": \"string\", \"pattern\": \"^[a-z][a-z0-9-]*$\" }" +
            "  }," +
            "  \"required\": [ \"serviceId\" ]" +
            '}';

    private static final String REPO_SCHEMA =
            "{ \"type\": \"object\", \"properties\": { \"serviceId\": { \"type\": \"string\" } } }";

    private static final String APP_IDENTITY_SCHEMA =
            '{' +
            "  \"type\": \"object\"," +
            "  \"properties\": { \"serviceId\": { \"type\": \"string\" } }," +
            "  \"required\": [ \"serviceId\" ]" +
            '}';

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
            try {
                builder.metadataProperties(new MetadataPropertiesConfig(
                        Jackson.readTree(PROJECT_SCHEMA),
                        Jackson.readTree(REPO_SCHEMA),
                        Jackson.readTree(APP_IDENTITY_SCHEMA)));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    };

    @RegisterExtension
    static final CentralDogmaExtension plainDogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    private static WebClient client;
    private static WebClient plainClient;

    @BeforeAll
    static void setUp() throws Exception {
        client = newSystemAdminClient(dogma);
        plainClient = newSystemAdminClient(plainDogma);
    }

    private static WebClient newSystemAdminClient(CentralDogmaExtension extension) throws Exception {
        // Log in with a session cookie; an access token cannot be used here because creating one is
        // itself subject to the appIdentity schema under test.
        final URI uri = extension.httpClient().uri();
        final AggregatedHttpResponse response = login(extension.httpClient(),
                                                      TestAuthMessageUtil.USERNAME,
                                                      TestAuthMessageUtil.PASSWORD);
        final Cookie sessionCookie = getSessionCookie(response);
        final String csrfToken = Jackson.readTree(response.contentUtf8()).get("csrf_token").asText();
        return WebClient.builder(uri)
                        .addHeader(SessionUtil.X_CSRF_TOKEN, csrfToken)
                        .addHeader(HttpHeaderNames.COOKIE, sessionCookie.toCookieHeader())
                        .build();
    }

    @Test
    void exposesDeclaredSchemas() throws Exception {
        final AggregatedHttpResponse res = client.get(API_V1_PATH_PREFIX + "metadataProperties")
                                                 .aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        final JsonNode schemas = Jackson.readTree(res.contentUtf8());
        assertThat(schemas.get("project")).isEqualTo(Jackson.readTree(PROJECT_SCHEMA));
        assertThat(schemas.get("repo")).isEqualTo(Jackson.readTree(REPO_SCHEMA));
        assertThat(schemas.get("appIdentity")).isEqualTo(Jackson.readTree(APP_IDENTITY_SCHEMA));

        final AggregatedHttpResponse plainRes = plainClient.get(API_V1_PATH_PREFIX + "metadataProperties")
                                                           .aggregate().join();
        assertThat(plainRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(Jackson.readTree(plainRes.contentUtf8())).isEqualTo(Jackson.readTree("{}"));
    }

    @Test
    void createsProjectWithDeclaredProperties() throws Exception {
        final AggregatedHttpResponse res = postJson(
                client, "projects",
                "{\"name\":\"prj1\",\"properties\":{\"serviceId\":\"foo-service\",\"undeclared\":\"x\"}}");
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);

        // Undeclared properties are dropped rather than stored.
        final JsonNode metadata = projectMetadata(client, "prj1");
        assertThat(metadata.get("properties")).isEqualTo(Jackson.readTree("{\"serviceId\":\"foo-service\"}"));
    }

    @Test
    void rejectsProjectPropertiesViolatingSchema() {
        AggregatedHttpResponse res = postJson(client, "projects", "{\"name\":\"prj-invalid\"}");
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.contentUtf8()).contains("serviceId");

        res = postJson(client, "projects", "{\"name\":\"prj-invalid\",\"properties\":{\"serviceId\":42}}");
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        res = postJson(client, "projects",
                       "{\"name\":\"prj-invalid\",\"properties\":{\"serviceId\":\"FOO\"}}");
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        res = postJson(client, "projects", "{\"name\":\"prj-invalid\",\"properties\":[\"a\"]}");
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.contentUtf8()).contains("must be a JSON object");

        // An object with only undeclared keys is equivalent to an empty one.
        res = postJson(client, "projects", "{\"name\":\"prj-invalid\",\"properties\":{\"foo\":\"x\"}}");
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.contentUtf8()).contains("serviceId");

        // An explicit JSON null is equivalent to an absent field.
        res = postJson(client, "projects", "{\"name\":\"prj-invalid\",\"properties\":null}");
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.contentUtf8()).contains("serviceId");
    }

    @Test
    void rejectsRepositoryPropertiesViolatingSchema() {
        final AggregatedHttpResponse created = postJson(
                client, "projects", "{\"name\":\"prj4\",\"properties\":{\"serviceId\":\"repo-neg\"}}");
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED);

        final AggregatedHttpResponse res = postJson(
                client, "projects/prj4/repos", "{\"name\":\"badrepo\",\"properties\":{\"serviceId\":123}}");
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.contentUtf8()).contains("serviceId");
    }

    @Test
    void mutationsPreserveProperties() throws Exception {
        AggregatedHttpResponse res = postJson(
                client, "projects", "{\"name\":\"prj3\",\"properties\":{\"serviceId\":\"mut-service\"}}");
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);
        res = postJson(client, "projects/prj3/repos",
                       "{\"name\":\"repo1\",\"properties\":{\"serviceId\":\"mut-repo\"}}");
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);

        // Removing and unremoving a project rewrites the whole project metadata.
        assertThat(client.delete(API_V1_PATH_PREFIX + "projects/prj3").aggregate().join().status())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(unremove(client, "projects/prj3").status()).isEqualTo(HttpStatus.OK);

        // Removing and restoring a repository rewrites its repository metadata.
        assertThat(client.delete(API_V1_PATH_PREFIX + "projects/prj3/repos/repo1")
                         .aggregate().join().status()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(unremove(client, "projects/prj3/repos/repo1").status()).isEqualTo(HttpStatus.OK);

        final JsonNode metadata = projectMetadata(client, "prj3");
        assertThat(metadata.get("properties")).isEqualTo(Jackson.readTree("{\"serviceId\":\"mut-service\"}"));
        assertThat(metadata.get("repos").get("repo1").get("properties"))
                .isEqualTo(Jackson.readTree("{\"serviceId\":\"mut-repo\"}"));

        // Deactivating and activating an app identity rebuilds its registry entry.
        res = client.post(API_V1_PATH_PREFIX + "appIdentities",
                          QueryParams.of("appId", "app-mut", "type", "TOKEN",
                                         "properties", "{\"serviceId\":\"mut-app\"}"),
                          HttpData.empty()).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(patchJson(client, "appIdentities/app-mut", "{\"status\":\"inactive\"}").status())
                .isEqualTo(HttpStatus.OK);
        assertThat(patchJson(client, "appIdentities/app-mut", "{\"status\":\"active\"}").status())
                .isEqualTo(HttpStatus.OK);
        await().untilAsserted(() -> {
            final AggregatedHttpResponse listRes = client.get(API_V1_PATH_PREFIX + "appIdentities")
                                                         .aggregate().join();
            JsonNode found = null;
            for (JsonNode appIdentity : Jackson.readTree(listRes.contentUtf8())) {
                if ("app-mut".equals(appIdentity.get("appId").asText())) {
                    found = appIdentity;
                }
            }
            assertThat(found).isNotNull();
            assertThat(found.get("properties")).isEqualTo(Jackson.readTree("{\"serviceId\":\"mut-app\"}"));
        });
    }

    @Test
    void createsRepositoryWithPropertiesAndPreservesProjectProperties() throws Exception {
        AggregatedHttpResponse res = postJson(
                client, "projects", "{\"name\":\"prj2\",\"properties\":{\"serviceId\":\"bar-service\"}}");
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);

        res = postJson(client, "projects/prj2/repos",
                       "{\"name\":\"repo1\",\"properties\":{\"serviceId\":\"baz-service\",\"u\":\"v\"}}");
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);

        // The repository schema has no required property, so a repository can be created without one.
        res = postJson(client, "projects/prj2/repos", "{\"name\":\"repo2\"}");
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);

        final JsonNode metadata = projectMetadata(client, "prj2");
        // Adding repositories rewrites the project metadata; the project properties must survive it.
        assertThat(metadata.get("properties")).isEqualTo(Jackson.readTree("{\"serviceId\":\"bar-service\"}"));
        assertThat(metadata.get("repos").get("repo1").get("properties"))
                .isEqualTo(Jackson.readTree("{\"serviceId\":\"baz-service\"}"));
        assertThat(metadata.get("repos").get("repo2").get("properties")).isNull();
    }

    @Test
    void createsAppIdentityWithProperties() throws Exception {
        final AggregatedHttpResponse res = client.post(
                API_V1_PATH_PREFIX + "appIdentities",
                QueryParams.of("appId", "app-props", "type", "TOKEN",
                               "properties", "{\"serviceId\":\"qux-service\",\"undeclared\":\"x\"}"),
                HttpData.empty()).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(Jackson.readTree(res.contentUtf8()).get("properties"))
                .isEqualTo(Jackson.readTree("{\"serviceId\":\"qux-service\"}"));

        // The properties must survive the round trip through /tokens.json.
        await().untilAsserted(() -> {
            final AggregatedHttpResponse listRes = client.get(API_V1_PATH_PREFIX + "appIdentities")
                                                         .aggregate().join();
            JsonNode found = null;
            for (JsonNode appIdentity : Jackson.readTree(listRes.contentUtf8())) {
                if ("app-props".equals(appIdentity.get("appId").asText())) {
                    found = appIdentity;
                }
            }
            assertThat(found).isNotNull();
            assertThat(found.get("properties"))
                    .isEqualTo(Jackson.readTree("{\"serviceId\":\"qux-service\"}"));
        });

        AggregatedHttpResponse badRes = client.post(
                API_V1_PATH_PREFIX + "appIdentities",
                QueryParams.of("appId", "app-no-props", "type", "TOKEN"),
                HttpData.empty()).aggregate().join();
        assertThat(badRes.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRes.contentUtf8()).contains("serviceId");

        badRes = client.post(
                API_V1_PATH_PREFIX + "appIdentities",
                QueryParams.of("appId", "app-bad-props", "type", "TOKEN", "properties", "not-json"),
                HttpData.empty()).aggregate().join();
        assertThat(badRes.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        badRes = client.post(
                API_V1_PATH_PREFIX + "appIdentities",
                QueryParams.of("appId", "app-non-object", "type", "TOKEN", "properties", "42"),
                HttpData.empty()).aggregate().join();
        assertThat(badRes.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRes.contentUtf8()).contains("must be a JSON object");
    }

    @Test
    void ignoresPropertiesWhenNothingDeclared() throws Exception {
        AggregatedHttpResponse res = postJson(
                plainClient, "projects",
                "{\"name\":\"prj-plain\",\"properties\":{\"serviceId\":\"foo-service\"}}");
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);

        res = postJson(plainClient, "projects/prj-plain/repos",
                       "{\"name\":\"repo1\",\"properties\":{\"serviceId\":\"foo-service\"}}");
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);

        final JsonNode metadata = projectMetadata(plainClient, "prj-plain");
        assertThat(metadata.get("properties")).isNull();
        assertThat(metadata.get("repos").get("repo1").get("properties")).isNull();

        final AggregatedHttpResponse appRes = plainClient.post(
                API_V1_PATH_PREFIX + "appIdentities",
                QueryParams.of("appId", "app-plain", "type", "TOKEN",
                               "properties", "{\"serviceId\":\"foo-service\"}"),
                HttpData.empty()).aggregate().join();
        assertThat(appRes.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(Jackson.readTree(appRes.contentUtf8()).get("properties")).isNull();

        // An explicit JSON null is equivalent to an absent field.
        res = postJson(plainClient, "projects", "{\"name\":\"prj-plain2\",\"properties\":null}");
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);
    }

    private static AggregatedHttpResponse postJson(WebClient client, String path, String body) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, API_V1_PATH_PREFIX + path,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        return client.execute(headers, body).aggregate().join();
    }

    private static AggregatedHttpResponse patchJson(WebClient client, String path, String body) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + path,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        return client.execute(headers, body).aggregate().join();
    }

    private static AggregatedHttpResponse unremove(WebClient client, String path) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + path,
                                                         HttpHeaderNames.CONTENT_TYPE,
                                                         "application/json-patch+json");
        return client.execute(headers, "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]")
                     .aggregate().join();
    }

    private static JsonNode projectMetadata(WebClient client, String projectName) throws Exception {
        final AggregatedHttpResponse res = client.get(API_V1_PATH_PREFIX + "projects/" + projectName)
                                                 .aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        return Jackson.readTree(res.contentUtf8());
    }
}
