/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.centraldogma.server;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.TemplateProcessingException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.api.variable.Variable;
import com.linecorp.centraldogma.server.internal.api.variable.VariableType;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class VariableTemplateCrudTest {

    private static final String TEST_PROJECT = "testProject";
    private static final String TEST_REPO_1 = "testRepo1";
    private static final String TEST_REPO_2 = "testRepo2";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected String accessToken() {
            return getAccessToken(WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                                  USERNAME, PASSWORD, true);
        }

        @Override
        protected void configureClient(ArmeriaCentralDogmaBuilder builder) {
            builder.clientConfigurator(cb -> {
                cb.decorator(LoggingClient.newDecorator());
            });
        }
    };

    private CentralDogmaRepository testRepo1;
    private CentralDogmaRepository testRepo2;
    private BlockingWebClient httpClient;

    @BeforeEach
    void beforeEach() {
        final CentralDogma client = dogma.client();
        try {
            client.removeProject(TEST_PROJECT).join();
            client.purgeProject(TEST_PROJECT).join();
        } catch (Exception e) {
            // Ignore
        }

        client.createProject(TEST_PROJECT).join();
        testRepo1 = client.createRepository(TEST_PROJECT, TEST_REPO_1).join();
        testRepo2 = client.createRepository(TEST_PROJECT, TEST_REPO_2).join();
        httpClient = dogma.blockingHttpClient();
    }

    @Test
    void applyTemplateWithProjectLevelStringVariable() {
        createVariable(TEST_PROJECT, null, "serverName", VariableType.STRING, "production-server");

        final String templateContent = "{ \"server\": \"${vars.serverName}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{ \"server\": \"production-server\" }");
    }

    @Test
    void applyTemplateWithProjectLevelJsonVariable() {
        final String jsonValue = "{\"host\":\"localhost\",\"port\":8080}";
        createVariable(TEST_PROJECT, null, "dbConfig", VariableType.JSON, jsonValue);

        final String templateContent = "{ \"database\": \"${vars.dbConfig.host}:${vars.dbConfig.port}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/db.json", templateContent);
        testRepo1.commit("Add db template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/db.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{ \"database\": \"localhost:8080\" }");

        final Entry<JsonNode> entryWithNoTemplate =
                testRepo1.file(Query.ofJson("/db.json"))
                         .get()
                         .join();
        assertThatJson(entryWithNoTemplate.content()).isEqualTo(templateContent);
    }

    @Test
    void applyTemplateWithRepoLevelVariable() {
        createVariable(TEST_PROJECT, TEST_REPO_1, "environment", VariableType.STRING, "staging");

        final String templateContent = "{ \"environment\": \"${vars.environment}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/env.json", templateContent);
        testRepo1.commit("Add env template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/env.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{ \"environment\": \"staging\" }");

        testRepo2.commit("Add env template in repo2", change).push().join();
        assertThatThrownBy(() -> {
            // repo1 variable should not be accessible in repo2
            testRepo2.file(Query.ofText("/env.json"))
                     .applyTemplate(true)
                     .get()
                     .join();
        }).hasCauseInstanceOf(TemplateProcessingException.class);
    }

    @Test
    void repoLevelVariableOverridesProjectLevelVariable() {
        createVariable(TEST_PROJECT, null, "version", VariableType.STRING, "1.0.0");
        createVariable(TEST_PROJECT, TEST_REPO_1, "version", VariableType.STRING, "2.0.0");

        final String templateContent = "Version: ${vars.version}";
        final Change<String> change = Change.ofTextUpsert("/version.txt", templateContent);
        testRepo1.commit("Add version template", change).push().join();

        // Repo-level should override
        final Entry<String> entry = testRepo1.file(Query.ofText("/version.txt"))
                                             .applyTemplate(true)
                                             .get()
                                             .join();

        assertThat(entry.content()).isEqualTo("Version: 2.0.0\n");

        testRepo2.commit("Add version template", change).push().join();
        final Entry<String> entry2 = testRepo2.file(Query.ofText("/version.txt"))
                                              .applyTemplate(true)
                                              .get()
                                              .join();
        // Should use project-level variable as repo2 does not have its own variable.
        assertThat(entry2.content()).isEqualTo("Version: 1.0.0\n");
    }

    @Test
    void repoLevelJsonVariableOverridesProjectLevelJsonVariable() {
        final String projectJson = "{\"host\":\"project-db.com\",\"port\":5432}";
        createVariable(TEST_PROJECT, null, "database", VariableType.JSON, projectJson);
        final String repoJson = "{\"host\":\"repo-db.com\", \"port\":3306}";
        createVariable(TEST_PROJECT, TEST_REPO_1, "database", VariableType.JSON, repoJson);

        //language=json5
        final String templateContent = '{' +
                                       "  // Using both host and port from the variable\n" +
                                       "  \"db\": \"${vars.database.host}:${vars.database.port}\"\n" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/db-override.json5", templateContent);
        testRepo1.commit("Add db override template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/db-override.json5"))
                                               .applyTemplate(true)
                                               .viewRaw(true)
                                               .get()
                                               .join();

        assertThat(entry.rawContent())
                //language=json5
                .isEqualTo('{' +
                           "  // Using both host and port from the variable\n" +
                           "  \"db\": \"repo-db.com:3306\"\n" +
                           "}\n");
    }

    @Test
    void mixedVariableOverride() {
        createVariable(TEST_PROJECT, null, "projectOnly", VariableType.STRING, "from-project");
        createVariable(TEST_PROJECT, null, "shared", VariableType.STRING, "project-value");

        createVariable(TEST_PROJECT, TEST_REPO_1, "shared", VariableType.STRING, "repo-value");
        createVariable(TEST_PROJECT, TEST_REPO_1, "repoOnly", VariableType.STRING, "from-repo");

        final String templateContent =
                "ProjectOnly: ${vars.projectOnly}\n" +
                "Shared: ${vars.shared}\n" +
                "RepoOnly: ${vars.repoOnly}";
        final Change<String> change = Change.ofTextUpsert("/mixed.txt", templateContent);
        testRepo1.commit("Add mixed template", change).push().join();

        final Entry<String> entry = testRepo1.file(Query.ofText("/mixed.txt"))
                                             .applyTemplate(true)
                                             .get()
                                             .join();

        // Verify: projectOnly from project, shared from repo (override), repoOnly from repo
        assertThat(entry.content()).isEqualTo(
                "ProjectOnly: from-project\n" +
                "Shared: repo-value\n" +
                "RepoOnly: from-repo\n");
    }

    @Test
    void deleteRepoVariableRestoresProjectVariable() {
        createVariable(TEST_PROJECT, null, "config", VariableType.STRING, "project-config");
        createVariable(TEST_PROJECT, TEST_REPO_1, "config", VariableType.STRING, "repo-config");

        final String templateContent = "Config: ${vars.config}";
        final Change<String> change = Change.ofTextUpsert("/config-override.txt", templateContent);
        testRepo1.commit("Add config override template", change).push().join();

        // Read with repo variable - should show repo value
        Entry<String> entry = testRepo1.file(Query.ofText("/config-override.txt"))
                                       .applyTemplate(true)
                                       .get()
                                       .join();
        assertThat(entry.content()).isEqualTo("Config: repo-config\n");

        deleteVariable(TEST_PROJECT, TEST_REPO_1, "config");

        // Read again - should now show project value
        entry = testRepo1.file(Query.ofText("/config-override.txt"))
                         .applyTemplate(true)
                         .get()
                         .join();
        assertThat(entry.content()).isEqualTo("Config: project-config\n");
    }

    @Test
    void updateRepoVariableOverridesProjectVariable() {
        createVariable(TEST_PROJECT, null, "setting", VariableType.STRING, "project-setting");

        final String templateContent = "Setting: ${vars.setting}";
        final Change<String> change = Change.ofTextUpsert("/setting.txt", templateContent);
        testRepo1.commit("Add setting template", change).push().join();

        // Initially, should use project variable
        Entry<String> entry = testRepo1.file(Query.ofText("/setting.txt"))
                                       .applyTemplate(true)
                                       .get()
                                       .join();
        assertThat(entry.content()).isEqualTo("Setting: project-setting\n");

        createVariable(TEST_PROJECT, TEST_REPO_1, "setting", VariableType.STRING, "repo-setting");

        // Now should use repo variable (override)
        entry = testRepo1.file(Query.ofText("/setting.txt"))
                         .applyTemplate(true)
                         .get()
                         .join();
        assertThat(entry.content()).isEqualTo("Setting: repo-setting\n");

        updateVariable(TEST_PROJECT, TEST_REPO_1, "setting", VariableType.STRING, "updated-repo-setting");

        entry = testRepo1.file(Query.ofText("/setting.txt"))
                         .applyTemplate(true)
                         .get()
                         .join();
        assertThat(entry.content()).isEqualTo("Setting: updated-repo-setting\n");
    }

    @Test
    void applyTemplateWithMultipleVariables() {
        createVariable(TEST_PROJECT, null, "appName", VariableType.STRING, "MyApp");
        createVariable(TEST_PROJECT, null, "version", VariableType.STRING, "3.0.0");
        createVariable(TEST_PROJECT, TEST_REPO_1, "env", VariableType.STRING, "production");

        //language=YAML
        final String templateContent =
                "Application: ${vars.appName}\n" +
                "Version: ${vars.version}\n" +
                "Environment: ${vars.env}";
        final Change<String> change = Change.ofTextUpsert("/app-info.yaml", templateContent);
        testRepo1.commit("Add app info template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofYaml("/app-info.yaml"))
                                               .applyTemplate(true)
                                               .viewRaw(true)
                                               .get()
                                               .join();

        assertThat(entry.rawContent()).isEqualTo(
                "Application: MyApp\n" +
                "Version: 3.0.0\n" +
                "Environment: production\n");
    }

    @Test
    void applyTemplateWithComplexJsonVariable() throws Exception {
        //language=JSON
        final String complexJson =
                '{' +
                "  \"database\":{" +
                "    \"host\":\"db.example.com\"," +
                "    \"port\":5432," +
                "    \"credentials\":{" +
                "      \"username\":\"admin\"," +
                "      \"password\":\"pass123\"" +
                "    }" +
                "  }," +
                "  \"cache\":{" +
                "    \"enabled\":true," +
                "    \"ttl\":3600" +
                "  }" +
                '}';
        createVariable(TEST_PROJECT, null, "config", VariableType.JSON, complexJson);

        //language=YAML
        final String templateContent =
                "db-host: ${vars.config.database.host}\n" +
                "db-port: ${vars.config.database.port}\n" +
                "db-user: ${vars.config.database.credentials.username}\n" +
                "cache-enabled: ${vars.config.cache.enabled}\n" +
                "cache-ttl: ${vars.config.cache.ttl}";
        final Change<JsonNode> change = Change.ofYamlUpsert("/db-config.yaml", templateContent);
        testRepo1.commit("Add DB config template", change).push().join();

        final Entry<String> entry = testRepo1.file(Query.ofText("/db-config.yaml"))
                                             .applyTemplate(true)
                                             .viewRaw(true)
                                             .get()
                                             .join();

        assertThat(entry.rawContent())
                .isEqualTo("db-host: db.example.com\n" +
                           "db-port: 5432\n" +
                           "db-user: admin\n" +
                           "cache-enabled: true\n" +
                           "cache-ttl: 3600\n");
    }

    @Test
    void applyTemplateWithFreemarkerDirectives() {
        createVariable(TEST_PROJECT, null, "enabled", VariableType.JSON, "true");
        createVariable(TEST_PROJECT, null, "items", VariableType.JSON, "[\"apple\",\"banana\",\"cherry\"]");

        final String templateContent =
                "<#if vars.enabled>\n" +
                "Feature is enabled\n" +
                "</#if>\n" +
                "Items:\n" +
                "<#list vars.items as item>\n" +
                "- ${item}\n" +
                "</#list>";
        final Change<String> change = Change.ofTextUpsert("/directives.yaml.ftl", templateContent);
        testRepo1.commit("Add template with directives", change).push().join();

        // Read file with template applied
        final Entry<String> entry = testRepo1.file(Query.ofText("/directives.yaml.ftl"))
                                             .applyTemplate(true)
                                             .get()
                                             .join();

        assertThat(entry.content()).isEqualTo(
                "Feature is enabled\n" +
                "Items:\n" +
                "- apple\n" +
                "- banana\n" +
                "- cherry\n");
    }

    @Test
    void applyTemplateWithJsonOutput() throws Exception {
        createVariable(TEST_PROJECT, null, "apiKey", VariableType.STRING, "secret-key-123");
        createVariable(TEST_PROJECT, null, "timeout", VariableType.JSON, "30");

        final String templateContent =
                "{\n" +
                "  \"apiKey\": \"${vars.apiKey}\",\n" +
                "  \"timeout\": ${vars.timeout}\n" +
                '}';
        final Change<String> change = Change.ofTextUpsert("/config.tmpl", templateContent);
        testRepo1.commit("Add JSON template", change).push().join();

        // Read file with template applied and parse as JSON
        final Entry<String> entry = testRepo1.file(Query.ofText("/config.tmpl"))
                                             .applyTemplate(true)
                                             .get()
                                             .join();

        // Parse the result as JSON to verify
        final JsonNode jsonResult = Jackson.readTree(entry.content());
        assertThatJson(jsonResult).node("apiKey").isEqualTo("secret-key-123");
        assertThatJson(jsonResult).node("timeout").isEqualTo(30);
    }

    @Test
    void applyTemplateWithoutVariablesShouldWork() {
        final String templateContent = "Static content without variables";
        final Change<String> change = Change.ofTextUpsert("/static.txt", templateContent);
        testRepo1.commit("Add static file", change).push().join();

        final Entry<String> entry = testRepo1.file(Query.ofText("/static.txt"))
                                             .applyTemplate(true)
                                             .get()
                                             .join();

        assertThat(entry.content()).isEqualTo("Static content without variables\n");
    }

    @Test
    void readFileWithoutApplyTemplate() {
        createVariable(TEST_PROJECT, null, "varName", VariableType.STRING, "\"value\"");

        final String templateContent = "Variable: ${vars.varName}";
        final Change<String> change = Change.ofTextUpsert("/template.txt", templateContent);
        testRepo1.commit("Add template", change).push().join();

        final Entry<String> entry = testRepo1.file(Query.ofText("/template.txt"))
                                             .get()
                                             .join();

        assertThat(entry.content()).isEqualTo("Variable: ${vars.varName}\n");
    }

    @Test
    void applyTemplateWithUndefinedVariableShouldFail() {
        final String templateContent = "Value: ${vars.nonExistentVar}";
        final Change<String> change = Change.ofTextUpsert("/fail.txt", templateContent);
        testRepo1.commit("Add failing template", change).push().join();

        assertThatThrownBy(() -> testRepo1.file(Query.ofText("/fail.txt"))
                                          .applyTemplate(true)
                                          .get()
                                          .join())
                .hasCauseInstanceOf(TemplateProcessingException.class);
    }

    @Test
    void applyTemplateWithMultipleFiles() {
        createVariable(TEST_PROJECT, null, "prefix", VariableType.STRING, "PREFIX");

        final Change<String> change1 = Change.ofTextUpsert("/file1.txt", "${vars.prefix}-1");
        final Change<String> change2 = Change.ofTextUpsert("/file2.txt", "${vars.prefix}-2");
        testRepo1.commit("Add templates", change1, change2).push().join();

        final Map<String, Entry<?>> entries = testRepo1.file(PathPattern.of("/*.txt"))
                                                       .applyTemplate(true)
                                                       .get()
                                                       .join();

        assertThat(entries).hasSize(2);
        assertThat(entries.get("/file1.txt").content()).isEqualTo("PREFIX-1\n");
        assertThat(entries.get("/file2.txt").content()).isEqualTo("PREFIX-2\n");
    }

    @Test
    void updateVariableAndReadTemplate() {
        createVariable(TEST_PROJECT, null, "counter", VariableType.JSON, "1");

        final String templateContent = "Count: ${vars.counter}";
        final Change<String> change = Change.ofTextUpsert("/counter.txt", templateContent);
        testRepo1.commit("Add counter template", change).push().join();

        Entry<String> entry = testRepo1.file(Query.ofText("/counter.txt"))
                                       .applyTemplate(true)
                                       .get()
                                       .join();
        assertThat(entry.content()).isEqualTo("Count: 1\n");

        updateVariable(TEST_PROJECT, null, "counter", VariableType.JSON, "2");

        entry = testRepo1.file(Query.ofText("/counter.txt"))
                         .applyTemplate(true)
                         .get()
                         .join();
        assertThat(entry.content()).isEqualTo("Count: 2\n");
    }

    @Test
    void deleteVariableAndReadTemplate() {
        createVariable(TEST_PROJECT, null, "temp", VariableType.STRING, "value");

        final String templateContent = "Temp: ${vars.temp}";
        final Change<String> change = Change.ofTextUpsert("/temp.txt", templateContent);
        testRepo1.commit("Add temp template", change).push().join();

        final Entry<String> entry = testRepo1.file(Query.ofText("/temp.txt"))
                                             .applyTemplate(true)
                                             .get()
                                             .join();
        assertThat(entry.content()).isEqualTo("Temp: value\n");

        // Delete the variable
        deleteVariable(TEST_PROJECT, null, "temp");

        // Reading with template applied should now fail
        assertThatThrownBy(() -> testRepo1.file(Query.ofText("/temp.txt"))
                                          .applyTemplate(true)
                                          .get()
                                          .join())
                .hasCauseInstanceOf(TemplateProcessingException.class)
                .hasMessageContaining("Failed to process the template for /temp.txt.")
                .hasMessageContaining("The following has evaluated to null or missing:\n" +
                                      "==> vars.temp");
    }

    // Tests for file-level variables (.variables.json, .variables.json5, .variables.yaml)

    @Test
    void applyTemplateWithDefaultVariablesJsonFile() {
        // Create a .variables.json file in the root directory
        final String variablesJson = "{\"serverName\": \"json-server\", \"port\": 8080}";
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/.variables.json", variablesJson);
        testRepo1.commit("Add .variables.json", variablesChange).push().join();

        final String templateContent = "{ \"server\": \"${vars.serverName}:${vars.port}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{ \"server\": \"json-server:8080\" }");
    }

    @Test
    void applyTemplateWithDefaultVariablesJson5File() {
        //language=JSON5
        final String variablesJson5 = "{\n" +
                                      "  // Server configuration\n" +
                                      "  serverName: \"json5-server\",\n" +
                                      "  port: 9090\n" +
                                      '}';
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/.variables.json5", variablesJson5);
        testRepo1.commit("Add .variables.json5", variablesChange).push().join();

        //language=JSON5
        final String templateContent = "{ server: \"${vars.serverName}:${vars.port}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json5", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json5"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{\"server\": \"json5-server:9090\"}");
    }

    @Test
    void applyTemplateWithDefaultVariablesYamlFile() {
        final String variablesYaml = "serverName: yaml-server\nport: 7070";
        final Change<String> variablesChange = Change.ofTextUpsert("/.variables.yaml", variablesYaml);
        testRepo1.commit("Add .variables.yaml", variablesChange).push().join();

        final String templateContent = "{ \"server\": \"${vars.serverName}:${vars.port}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        // Read file with template applied
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{\"server\": \"yaml-server:7070\"}");
    }

    @Test
    void applyTemplateWithDefaultVariablesYmlFile() {
        // Create a .variables.yml file in the root directory
        final String variablesYml = "serverName: yml-server\nport: 6060";
        final Change<String> variablesChange = Change.ofTextUpsert("/.variables.yml", variablesYml);
        testRepo1.commit("Add .variables.yml", variablesChange).push().join();

        // Create a template file
        final String templateContent = "{ \"server\": \"${vars.serverName}:${vars.port}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        // Read file with template applied
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{\"server\": \"yml-server:6060\"}");
    }

    @Test
    void variableFilesPriority_jsonOverJson5() {
        // .variables.json should take priority over .variables.json5
        final String variablesJson = "{\"priority\": \"json\"}";
        final Change<JsonNode> jsonChange = Change.ofJsonUpsert("/.variables.json", variablesJson);

        //language=JSON5
        final String variablesJson5 = "{priority: \"json5\"}";
        final Change<JsonNode> json5Change = Change.ofJsonUpsert("/.variables.json5", variablesJson5);

        testRepo1.commit("Add variable files", jsonChange, json5Change).push().join();

        //language=JSON5
        final String templateContent = "{ language : \"${vars.priority}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/priority.json5", templateContent);
        testRepo1.commit("Add priority template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/priority.json5"))
                                               .viewRaw(true)
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThat(entry.rawContent()).isEqualTo("{ language : \"json\" }\n");
    }

    @Test
    void variableFilesPriority_json5OverYaml() {
        // .variables.json5 should take priority over .variables.yaml
        //language=JSON5
        final String variablesJson5 = "{priority: \"json5\"}";
        final Change<JsonNode> json5Change = Change.ofJsonUpsert("/.variables.json5", variablesJson5);
        final String variablesYaml = "priority: yaml";
        final Change<String> yamlChange = Change.ofTextUpsert("/.variables.yaml", variablesYaml);
        testRepo1.commit("Add variable files", json5Change, yamlChange).push().join();

        final String templateContent = "{ \"priority\": \"${vars.priority}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/priority.json", templateContent);
        testRepo1.commit("Add priority template", change).push().join();

        // .variables.json5 should be used
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/priority.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{\"priority\": \"json5\"}");
    }

    @Test
    void variableFilesPriority_yamlOverYml() {
        // .variables.yaml should take priority over .variables.yml
        final String variablesYaml = "priority: yaml";
        final Change<String> yamlChange = Change.ofTextUpsert("/.variables.yaml", variablesYaml);
        final String variablesYml = "priority: yml";
        final Change<String> ymlChange = Change.ofTextUpsert("/.variables.yml", variablesYml);
        testRepo1.commit("Add variable files", yamlChange, ymlChange).push().join();

        final String templateContent = "{ \"priority\": \"${vars.priority}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/priority.json", templateContent);
        testRepo1.commit("Add priority template", change).push().join();

        // .variables.yaml should be used
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/priority.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{\"priority\": \"yaml\"}");
    }

    @Test
    void variableFilesInSameDirectoryTakesPrecedenceOverRoot() {
        final String rootVariables = "{\"location\": \"root\"}";
        final Change<JsonNode> rootChange = Change.ofJsonUpsert("/.variables.json", rootVariables);

        final String subdirVariables = "{\"location\": \"subdir\"}";
        final Change<JsonNode> subdirChange = Change.ofJsonUpsert("/subdir/.variables.json", subdirVariables);

        testRepo1.commit("Add variable files", rootChange, subdirChange).push().join();

        final String templateContent = "{ \"location\": \"${vars.location}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/subdir/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        // Subdirectory variables should be used for overridden keys
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/subdir/config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{\"location\": \"subdir\"}");
    }

    @Test
    void variableFilesFromRootAndSubdirectoryAreMerged() {
        // Root has 'rootVar' and 'shared', subdirectory has 'subdirVar' and 'shared'
        final String rootVariables = "{\"rootVar\": \"from-root\", \"shared\": \"root-value\"}";
        final Change<JsonNode> rootChange = Change.ofJsonUpsert("/.variables.json", rootVariables);

        final String subdirVariables = "{\"subdirVar\": \"from-subdir\", \"shared\": \"subdir-value\"}";
        final Change<JsonNode> subdirChange = Change.ofJsonUpsert("/subdir/.variables.json", subdirVariables);

        testRepo1.commit("Add variable files", rootChange, subdirChange).push().join();

        // Template uses variables from both root and subdirectory
        final String templateContent = '{' +
                                       "  \"root\": \"${vars.rootVar}\"," +
                                       "  \"subdir\": \"${vars.subdirVar}\"," +
                                       "  \"shared\": \"${vars.shared}\"" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/subdir/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        // All variables should be available, with subdirectory taking precedence for 'shared'
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/subdir/config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo('{' +
                                                  "  \"root\": \"from-root\"," +
                                                  "  \"subdir\": \"from-subdir\"," +
                                                  "  \"shared\": \"subdir-value\"" +
                                                  '}');
    }

    @Test
    void entryPathVariablesOverrideRootVariables() {
        // Root and entry path files have same variables, entry path should win
        final String rootVariables = "{\"env\": \"root-env\", \"region\": \"root-region\"}";
        final Change<JsonNode> rootChange = Change.ofJsonUpsert("/.variables.json", rootVariables);

        final String entryPathVariables = "{\"env\": \"entrypath-env\", \"region\": \"entrypath-region\"}";
        final Change<JsonNode> entryPathChange = Change.ofJsonUpsert("/subdir/.variables.json",
                                                                     entryPathVariables);

        testRepo1.commit("Add variable files", rootChange, entryPathChange).push().join();

        final String templateContent = "{ \"env\": \"${vars.env}\", \"region\": \"${vars.region}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/subdir/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        // Entry path variables should override root variables
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/subdir/config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo(
                "{\"env\": \"entrypath-env\", \"region\": \"entrypath-region\"}");
    }

    @Test
    void clientVariablesOverrideEntryPathVariables() {
        // Entry path and client files have same variables, client should win
        final String entryPathVariables =
                "{\"env\": \"entrypath-env\", \"region\": \"entrypath-region\", \"path\": \"entry-level\"}";
        final Change<JsonNode> entryPathChange = Change.ofJsonUpsert("/subdir/.variables.json",
                                                                     entryPathVariables);

        final String clientVariables =
                "{\"env\": \"client-env\", \"region\": \"client-region\", \"clientFile\": true}";
        final Change<JsonNode> clientChange = Change.ofJsonUpsert("/vars/client.json", clientVariables);

        testRepo1.commit("Add variable files", entryPathChange, clientChange).push().join();

        final String templateContent =
                '{' +
                "  \"env\": \"${vars.env}\", " +
                "  \"region\": \"${vars.region}\" , " +
                "  \"path\": \"${vars.path}\", " +
                "  \"client-file\": \"${vars.clientFile}\" " +
                '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/subdir/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        // Client variables should override entry path variables
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/subdir/config.json"))
                                               .applyTemplate("/vars/client.json")
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo(
                '{' +
                "  \"env\": \"client-env\", " +
                "  \"region\": \"client-region\"," +
                "  \"path\": \"entry-level\", " +
                "  \"client-file\": \"true\" " +
                '}');
    }

    @Test
    void variableFilesInRootDirectoryUsedWhenNoLocalVariableFile() {
        final String rootVariables = "{\"location\": \"root\"}";
        final Change<JsonNode> rootChange = Change.ofJsonUpsert("/.variables.json", rootVariables);
        testRepo1.commit("Add root variable file", rootChange).push().join();

        final String templateContent = "{ \"location\": \"${vars.location}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/subdir/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        // Root directory variables should be used
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/subdir/config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{\"location\": \"root\"}");
    }

    @Test
    void fileVariablesOverrideRepoVariables() {
        createVariable(TEST_PROJECT, TEST_REPO_1, "source", VariableType.STRING, "repo-level");

        final String variablesJson = "{\"source\": \"file-level\"}";
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/.variables.json", variablesJson);
        testRepo1.commit("Add .variables.json", variablesChange).push().join();
        final String templateContent = "{ \"source\": \"${vars.source}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        // File-level should override repo-level
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{\"source\": \"file-level\"}");
    }

    @Test
    void fileVariablesOverrideProjectVariables() {
        createVariable(TEST_PROJECT, null, "source", VariableType.STRING, "project-level");

        final String variablesJson = "{\"source\": \"file-level\"}";
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/.variables.json", variablesJson);
        testRepo1.commit("Add .variables.json", variablesChange).push().join();
        final String templateContent = "{ \"source\": \"${vars.source}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        // File-level should override project-level
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{\"source\": \"file-level\"}");
    }

    @Test
    void mixedVariablesFromAllLevels() {
        createVariable(TEST_PROJECT, null, "projectVar", VariableType.STRING, "from-project");
        createVariable(TEST_PROJECT, TEST_REPO_1, "repoVar", VariableType.STRING, "from-repo");

        final String variablesJson = "{\"fileVar\": \"from-file\"}";
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/.variables.json", variablesJson);
        testRepo1.commit("Add .variables.json", variablesChange).push().join();

        final String templateContent = '{' +
                                       "  \"project\": \"${vars.projectVar}\"," +
                                       "  \"repo\": \"${vars.repoVar}\"," +
                                       "  \"file\": \"${vars.fileVar}\"" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo('{' +
                                                  "  \"project\": \"from-project\"," +
                                                  "  \"repo\": \"from-repo\"," +
                                                  "  \"file\": \"from-file\"" +
                                                  '}');
    }

    @Test
    void allVariableSourcesMergedWithPrecedence() {
        // Set up all five variable sources with unique variables and one shared variable 'priority'
        // Priority order (lowest to highest): project < repo < root file < entry path file < client file
        createVariable(TEST_PROJECT, null, "projectVar", VariableType.STRING, "from-project");
        createVariable(TEST_PROJECT, null, "priority", VariableType.STRING, "project-priority");

        createVariable(TEST_PROJECT, TEST_REPO_1, "repoVar", VariableType.STRING, "from-repo");
        createVariable(TEST_PROJECT, TEST_REPO_1, "priority", VariableType.STRING, "repo-priority");

        final String rootVariables =
                "{\"rootFileVar\": \"from-root-file\", \"priority\": \"root-file-priority\"}";
        final Change<JsonNode> rootChange = Change.ofJsonUpsert("/.variables.json", rootVariables);

        final String entryPathVariables =
                "{\"entryPathVar\": \"from-entry-path\", \"priority\": \"entry-path-priority\"}";
        final Change<JsonNode> entryPathChange = Change.ofJsonUpsert("/subdir/.variables.json",
                                                                     entryPathVariables);

        final String clientVariables = "{\"clientVar\": \"from-client\", \"priority\": \"client-priority\"}";
        final Change<JsonNode> clientChange = Change.ofJsonUpsert("/vars/client.json", clientVariables);

        testRepo1.commit("Add variable files", rootChange, entryPathChange, clientChange).push().join();

        // Template uses variables from all sources
        final String templateContent = '{' +
                                       "  \"project\": \"${vars.projectVar}\"," +
                                       "  \"repo\": \"${vars.repoVar}\"," +
                                       "  \"rootFile\": \"${vars.rootFileVar}\"," +
                                       "  \"entryPath\": \"${vars.entryPathVar}\"," +
                                       "  \"client\": \"${vars.clientVar}\"," +
                                       "  \"priority\": \"${vars.priority}\"" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/subdir/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        // All variables should be available, with client file taking precedence for 'priority'
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/subdir/config.json"))
                                               .applyTemplate("/vars/client.json")
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo('{' +
                                                  "  \"project\": \"from-project\"," +
                                                  "  \"repo\": \"from-repo\"," +
                                                  "  \"rootFile\": \"from-root-file\"," +
                                                  "  \"entryPath\": \"from-entry-path\"," +
                                                  "  \"client\": \"from-client\"," +
                                                  "  \"priority\": \"client-priority\"" +
                                                  '}');
    }

    @Test
    void variablesJsonWithComplexNestedObject() {
        final String variablesJson = '{' +
                                     "  \"database\": {" +
                                     "    \"host\": \"db.example.com\"," +
                                     "    \"port\": 5432," +
                                     "    \"credentials\": {" +
                                     "      \"username\": \"admin\"" +
                                     "    }" +
                                     "  }" +
                                     '}';
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/.variables.json", variablesJson);
        testRepo1.commit("Add .variables.json", variablesChange).push().join();

        final String templateContent = '{' +
                                       "  \"db\": \"${vars.database.host}:${vars.database.port}\"," +
                                       "  \"user\": \"${vars.database.credentials.username}\"" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/db-config.json", templateContent);
        testRepo1.commit("Add db config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/db-config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo('{' +
                                                  "  \"db\": \"db.example.com:5432\"," +
                                                  "  \"user\": \"admin\"" +
                                                  '}');
    }

    @Test
    void variablesYamlWithComplexNestedObject() {
        //language=YAML
        final String variablesYaml = "database:\n" +
                                     "  host: db.example.com\n" +
                                     "  port: 5432\n" +
                                     "  credentials:\n" +
                                     "    username: admin";
        final Change<JsonNode> variablesChange = Change.ofYamlUpsert("/.variables.yaml", variablesYaml);
        testRepo1.commit("Add .variables.yaml", variablesChange).push().join();

        final String templateContent = '{' +
                                       "  \"db\": \"${vars.database.host}:${vars.database.port}\"," +
                                       "  \"user\": \"${vars.database.credentials.username}\"" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/db-config.json", templateContent);
        testRepo1.commit("Add db config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/db-config.json"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo('{' +
                                                  "\"db\": \"db.example.com:5432\"," +
                                                  "\"user\": \"admin\"" +
                                                  '}');
    }

    // Tests for custom variable files using applyTemplate(variableFile)

    @Test
    void applyTemplateWithCustomVariableFile() {
        final String customVariables = "{\"env\": \"production\", \"region\": \"us-east\"}";
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/vars/prod.json", customVariables);
        testRepo1.commit("Add custom variable file", variablesChange).push().join();

        final String templateContent = "{ \"env\": \"${vars.env}\", \"region\": \"${vars.region}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .applyTemplate("/vars/prod.json")
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{\"env\": \"production\", \"region\": \"us-east\"}");
    }

    @Test
    void applyTemplateWithCustomVariableFileOverridesDefaultVariableFile() {
        final String defaultVariables = "{\"env\": \"default\"}";
        final Change<JsonNode> defaultChange = Change.ofJsonUpsert("/.variables.json", defaultVariables);
        final String customVariables = "{\"env\": \"custom\"}";
        final Change<JsonNode> customChange = Change.ofJsonUpsert("/vars/custom.json", customVariables);
        testRepo1.commit("Add variable files", defaultChange, customChange).push().join();

        final String templateContent = "{ \"env\": \"${vars.env}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .applyTemplate("/vars/custom.json")
                                               .get()
                                               .join();

        // Custom variable file should override the default for overlapping keys
        assertThatJson(entry.content()).isEqualTo("{\"env\": \"custom\"}");
    }

    @Test
    void applyTemplateWithCustomVariableFileMergesWithDefaultVariableFile() {
        // Default has 'defaultVar' and 'shared', custom has 'customVar' and 'shared'
        final String defaultVariables = "{\"defaultVar\": \"from-default\", \"shared\": \"default-value\"}";
        final Change<JsonNode> defaultChange = Change.ofJsonUpsert("/.variables.json", defaultVariables);
        final String customVariables = "{\"customVar\": \"from-custom\", \"shared\": \"custom-value\"}";
        final Change<JsonNode> customChange = Change.ofJsonUpsert("/vars/custom.json", customVariables);
        testRepo1.commit("Add variable files", defaultChange, customChange).push().join();

        // Template uses variables from both default and custom files
        final String templateContent = '{' +
                                       "  \"default\": \"${vars.defaultVar}\"," +
                                       "  \"custom\": \"${vars.customVar}\"," +
                                       "  \"shared\": \"${vars.shared}\"" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .applyTemplate("/vars/custom.json")
                                               .get()
                                               .join();

        // All variables should be available, with custom taking precedence for 'shared'
        assertThatJson(entry.content()).isEqualTo('{' +
                                                  "  \"default\": \"from-default\"," +
                                                  "  \"custom\": \"from-custom\"," +
                                                  "  \"shared\": \"custom-value\"" +
                                                  '}');
    }

    @Test
    void applyTemplateWithCustomVariableFileCombinesWithProjectAndRepoVariables() {
        createVariable(TEST_PROJECT, null, "projectVar", VariableType.STRING, "from-project");
        createVariable(TEST_PROJECT, TEST_REPO_1, "repoVar", VariableType.STRING, "from-repo");
        final String customVariables = "{\"fileVar\": \"from-custom-file\"}";
        final Change<JsonNode> customChange = Change.ofJsonUpsert("/vars/custom.json", customVariables);
        testRepo1.commit("Add custom variable file", customChange).push().join();

        final String templateContent = '{' +
                                       "  \"project\": \"${vars.projectVar}\"," +
                                       "  \"repo\": \"${vars.repoVar}\"," +
                                       "  \"file\": \"${vars.fileVar}\"" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        // Read file with template applied using custom variable file
        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .applyTemplate("/vars/custom.json")
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo('{' +
                                                  "  \"project\": \"from-project\"," +
                                                  "  \"repo\": \"from-repo\"," +
                                                  "  \"file\": \"from-custom-file\"" +
                                                  '}');
    }

    @Test
    void applyTemplateWithMultipleCustomVariableFilesForDifferentEnvironments() {
        final String devVariables = "{\"env\": \"development\", \"debug\": true}";
        final Change<JsonNode> devChange = Change.ofJsonUpsert("/vars/dev.json", devVariables);
        final String prodVariables = "{\"env\": \"production\", \"debug\": false}";
        final Change<JsonNode> prodChange = Change.ofJsonUpsert("/vars/prod.json", prodVariables);
        testRepo1.commit("Add environment variable files", devChange, prodChange).push().join();

        //language=YAML
        final String templateContent = "hostname: dogma-${vars.env}.com\n" +
                                       "debug: ${vars.debug}";
        final Change<JsonNode> change = Change.ofYamlUpsert("/config.yaml", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        final Entry<JsonNode> devEntry = testRepo1.file(Query.ofYaml("/config.yaml"))
                                                  .applyTemplate("/vars/dev.json")
                                                  .viewRaw(true)
                                                  .get()
                                                  .join();
        assertThatJson(devEntry.content())
                .isEqualTo("{\"hostname\": \"dogma-development.com\", \"debug\": true}");
        assertThat(devEntry.rawContent())
                .isEqualTo("hostname: dogma-development.com\n" +
                           "debug: true\n");

        // Read file with prod environment
        final Entry<JsonNode> prodEntry = testRepo1.file(Query.ofYaml("/config.yaml"))
                                                   .applyTemplate("/vars/prod.json")
                                                   .viewRaw(true)
                                                   .get()
                                                   .join();

        assertThatJson(prodEntry.content())
                .isEqualTo("{\"hostname\": \"dogma-production.com\", \"debug\": false}");
        assertThat(prodEntry.rawContent())
                .isEqualTo("hostname: dogma-production.com\n" +
                           "debug: false\n");
    }

    @Test
    void applyTemplateWithCustomVariableFileToMultipleFiles() {
        final String customVariables = "{\"prefix\": \"CUSTOM\"}";
        final Change<JsonNode> customChange = Change.ofJsonUpsert("/vars/custom.json", customVariables);
        testRepo1.commit("Add custom variable file", customChange).push().join();

        // Create multiple template files
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/file1.json",
                                                             "{ \"value\": \"${vars.prefix}-1\" }");
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/file2.json",
                                                             "{ \"value\": \"${vars.prefix}-2\" }");
        testRepo1.commit("Add templates", change1, change2).push().join();

        // Read multiple files with template applied using custom variable file
        final Map<String, Entry<?>> entries = testRepo1.file(PathPattern.of("/*.json"))
                                                       .applyTemplate("/vars/custom.json")
                                                       .get()
                                                       .join();

        assertThat(entries).hasSize(2);
        assertThatJson(entries.get("/file1.json").content()).isEqualTo("{\"value\": \"CUSTOM-1\"}");
        assertThatJson(entries.get("/file2.json").content()).isEqualTo("{\"value\": \"CUSTOM-2\"}");
    }

    @Test
    void applyTemplateWithJson5CustomVariableFile() {
        //language=JSON5
        final String customVariables = "{\n" +
                                       "  // Environment configuration\n" +
                                       "  env: \"staging\",\n" +
                                       "  port: 8080\n" +
                                       '}';
        final Change<JsonNode> customChange = Change.ofJsonUpsert("/vars/staging.json5", customVariables);
        testRepo1.commit("Add custom JSON5 variable file", customChange).push().join();

        //language=JSON5
        final String templateContent = "{ env: \"${vars.env}\", port: \"${vars.port}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json5", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json5"))
                                               .applyTemplate("/vars/staging.json5")
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{\"env\": \"staging\", \"port\": \"8080\"}");
    }

    @Test
    void applyTemplateWithJsonPathQuery() {
        createVariable(TEST_PROJECT, null, "serverName", VariableType.STRING, "production-server");

        final String templateContent = '{' +
                                       "  \"server\": \"${vars.serverName}\"," +
                                       "  \"port\": 8080" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJsonPath("/config.json", "$.server"))
                                               .applyTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("\"production-server\"");
    }

    @Test
    void applyTemplateWithJsonPathQueryOnNestedObject() {
        final String jsonValue = "{\"host\":\"db.example.com\",\"port\":5432}";
        createVariable(TEST_PROJECT, null, "database", VariableType.JSON, jsonValue);

        final String templateContent = '{' +
                                       "  \"db\": {" +
                                       "    \"host\": \"${vars.database.host}\"," +
                                       "    \"port\": \"${vars.database.port}\"," +
                                       "    \"credentials\": {" +
                                       "      \"user\": \"admin\"" +
                                       "    }" +
                                       "  }" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/db.json", templateContent);
        testRepo1.commit("Add db template", change).push().join();

        final Entry<JsonNode> hostEntry = testRepo1.file(Query.ofJsonPath("/db.json", "$.db.host"))
                                                   .applyTemplate(true)
                                                   .get()
                                                   .join();
        assertThatJson(hostEntry.content()).isEqualTo("\"db.example.com\"");
    }

    @Test
    void applyTemplateWithJsonPathQueryWithFilter() {
        createVariable(TEST_PROJECT, null, "adminRole", VariableType.STRING, "admin");
        createVariable(TEST_PROJECT, null, "userRole", VariableType.STRING, "user");

        final String templateContent = '{' +
                                       "  \"users\": [" +
                                       "    {\"name\": \"Alice\", \"role\": \"${vars.adminRole}\"}," +
                                       "    {\"name\": \"Bob\", \"role\": \"${vars.userRole}\"}," +
                                       "    {\"name\": \"Charlie\", \"role\": \"${vars.adminRole}\"}" +
                                       "  ]" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/users.json", templateContent);
        testRepo1.commit("Add users template", change).push().join();

        // Query users with admin role using filter
        final Entry<JsonNode> adminUsers =
                testRepo1.file(Query.ofJsonPath("/users.json", "$.users[?(@.role == 'admin')]"))
                         .applyTemplate(true)
                         .get()
                         .join();

        assertThatJson(adminUsers.content()).isArray().ofLength(2);
        assertThatJson(adminUsers.content()).node("[0].name").isEqualTo("Alice");
        assertThatJson(adminUsers.content()).node("[1].name").isEqualTo("Charlie");

        // Query users with user role
        final Entry<JsonNode> regularUsers =
                testRepo1.file(Query.ofJsonPath("/users.json", "$.users[?(@.role == 'user')]"))
                         .applyTemplate(true)
                         .get()
                         .join();

        assertThatJson(regularUsers.content()).isArray().ofLength(1);
        assertThatJson(regularUsers.content()).node("[0].name").isEqualTo("Bob");
    }

    @Test
    void applyTemplateWithJsonPathQueryOnYaml() {
        createVariable(TEST_PROJECT, null, "environment", VariableType.STRING, "production");
        createVariable(TEST_PROJECT, null, "replicas", VariableType.JSON, "3");

        //language=YAML
        final String templateContent = "deployment:\n" +
                                       "  environment: ${vars.environment}\n" +
                                       "  replicas: ${vars.replicas}\n" +
                                       "  image: nginx:latest";
        final Change<JsonNode> change = Change.ofYamlUpsert("/deployment.yaml", templateContent);
        testRepo1.commit("Add deployment template", change).push().join();

        // Query specific field using JSON path
        final Entry<JsonNode> envEntry =
                testRepo1.file(Query.ofJsonPath("/deployment.yaml", "$.deployment.environment"))
                         .applyTemplate(true)
                         .get()
                         .join();
        assertThatJson(envEntry.content()).isEqualTo("\"production\"");

        final Entry<JsonNode> replicasEntry =
                testRepo1.file(Query.ofJsonPath("/deployment.yaml", "$.deployment.replicas"))
                         .applyTemplate(true)
                         .get()
                         .join();
        assertThatJson(replicasEntry.content()).isEqualTo("3");
    }

    @Test
    void applyTemplateWithJsonPathQueryOnJson5() {
        createVariable(TEST_PROJECT, null, "theme", VariableType.STRING, "dark");

        //language=JSON5
        final String templateContent = "{\n" +
                                       "  // Application settings\n" +
                                       "  settings: {\n" +
                                       "    theme: \"${vars.theme}\",\n" +
                                       "    language: \"en\"\n" +
                                       "  }\n" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/settings.json5", templateContent);
        testRepo1.commit("Add settings template", change).push().join();

        // Query theme using JSON path
        final Entry<JsonNode> themeEntry =
                testRepo1.file(Query.ofJsonPath("/settings.json5", "$.settings.theme"))
                         .applyTemplate(true)
                         .get()
                         .join();
        assertThatJson(themeEntry.content()).isEqualTo("\"dark\"");
    }

    @Test
    void applyTemplateWithJsonPathQueryUsingCustomVariableFile() {
        final String customVariables = "{\"env\": \"staging\", \"version\": \"2.0.0\"}";
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/vars/staging.json", customVariables);
        testRepo1.commit("Add custom variable file", variablesChange).push().join();

        final String templateContent = '{' +
                                       "  \"app\": {" +
                                       "    \"env\": \"${vars.env}\"," +
                                       "    \"version\": \"${vars.version}\"" +
                                       "  }" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/app.json", templateContent);
        testRepo1.commit("Add app template", change).push().join();

        // Apply template with custom variable file and JSON path query
        final Entry<JsonNode> envEntry =
                testRepo1.file(Query.ofJsonPath("/app.json", "$.app.env"))
                         .applyTemplate("/vars/staging.json")
                         .get()
                         .join();
        assertThatJson(envEntry.content()).isEqualTo("\"staging\"");

        final Entry<JsonNode> versionEntry =
                testRepo1.file(Query.ofJsonPath("/app.json", "$.app.version"))
                         .applyTemplate("/vars/staging.json")
                         .get()
                         .join();
        assertThatJson(versionEntry.content()).isEqualTo("\"2.0.0\"");
    }

    @Test
    void applyTemplateWithJsonPathQueryUsingDefaultVariableFile() {
        final String variablesJson = "{\"apiVersion\": \"v1\", \"kind\": \"ConfigMap\"}";
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/.variables.json", variablesJson);
        testRepo1.commit("Add .variables.json", variablesChange).push().join();

        final String templateContent = '{' +
                                       "  \"apiVersion\": \"${vars.apiVersion}\"," +
                                       "  \"kind\": \"${vars.kind}\"," +
                                       "  \"metadata\": {\"name\": \"my-config\"}" +
                                       '}';
        final Change<JsonNode> change = Change.ofJsonUpsert("/k8s-config.json", templateContent);
        testRepo1.commit("Add k8s config template", change).push().join();

        // Query specific fields after template application
        final Entry<JsonNode> apiVersionEntry =
                testRepo1.file(Query.ofJsonPath("/k8s-config.json", "$.apiVersion"))
                         .applyTemplate(true)
                         .get()
                         .join();
        assertThatJson(apiVersionEntry.content()).isEqualTo("\"v1\"");

        final Entry<JsonNode> kindEntry =
                testRepo1.file(Query.ofJsonPath("/k8s-config.json", "$.kind"))
                         .applyTemplate(true)
                         .get()
                         .join();
        assertThatJson(kindEntry.content()).isEqualTo("\"ConfigMap\"");
    }

    @Test
    void applyTemplateWithJsonPathQueryCannotUseViewRaw() {
        createVariable(TEST_PROJECT, null, "key", VariableType.STRING, "value");

        final String templateContent = "{ \"key\": \"${vars.key}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/test.json", templateContent);
        testRepo1.commit("Add test template", change).push().join();

        assertThatThrownBy(() -> {
            testRepo1.file(Query.ofJsonPath("/test.json", "$.key"))
                     .applyTemplate(true)
                     .viewRaw(true)
                     .get()
                     .join();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("JSON_PATH query cannot be used with raw view");
    }

    @Test
    void applyTemplateWithJsonPathQueryOnWatchedFile() throws InterruptedException {
        createVariable(TEST_PROJECT, null, "status", VariableType.STRING, "initializing");

        final String initialTemplate = "{ \"status\": \"${vars.status}\" }";
        final Change<JsonNode> initialChange = Change.ofJsonUpsert("/status.json", initialTemplate);
        final PushResult initialResult = testRepo1.commit("Initial status", initialChange).push().join();

        final String updatedTemplate = "{ \"status\": \"${vars.status}\", \"timestamp\": \"2025-01-08\" }";
        final Change<JsonNode> updateChange = Change.ofJsonUpsert("/status.json", updatedTemplate);
        final PushResult updateResult = testRepo1.commit("Update status template", updateChange).push()
                                                 .join();

        final CompletableFuture<Entry<JsonNode>> future =
                testRepo1.watch(Query.ofJsonPath("/status.json", "$.status"))
                         .applyTemplate(true)
                         .start(initialResult.revision());
        Thread.sleep(1000);
        // The future should not be done yet because the JSON path query result has not changed.
        assertThat(future).isNotDone();
        createVariable(TEST_PROJECT, null, "status", VariableType.STRING, "initialized");
        final Entry<JsonNode> entry = future.join();
        assertThatJson(entry.content()).isEqualTo("\"initialized\"");
        // Revision isn't changed because only the variable file in the dogma repo is changed.
        assertThat(entry.revision()).isEqualTo(updateResult.revision());
    }

    // Tests for watch operations with variable updates

    @Test
    void watcherShouldBeNotifiedWhenProjectVariableIsUpdated() throws InterruptedException {
        // Create initial variable and template
        createVariable(TEST_PROJECT, null, "version", VariableType.STRING, "1.0.0");

        final String templateContent = "{ \"version\": \"${vars.version}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/version.json", templateContent);
        final PushResult pushResult = testRepo1.commit("Add version template", change).push().join();

        // Start watching
        final CompletableFuture<Entry<JsonNode>> future =
                testRepo1.watch(Query.ofJson("/version.json"))
                         .applyTemplate(true)
                         .start(pushResult.revision());

        assertThat(future).isNotDone();

        // Update the project-level variable
        updateVariable(TEST_PROJECT, null, "version", VariableType.STRING, "2.0.0");

        // Watcher should be notified
        final Entry<JsonNode> entry = future.join();
        assertThatJson(entry.content()).isEqualTo("{ \"version\": \"2.0.0\" }");
    }

    @Test
    void watcherShouldBeNotifiedWhenRepoVariableIsUpdated() throws InterruptedException {
        // Create initial variable and template
        createVariable(TEST_PROJECT, TEST_REPO_1, "env", VariableType.STRING, "staging");

        final String templateContent = "{ \"environment\": \"${vars.env}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/env.json", templateContent);
        final PushResult pushResult = testRepo1.commit("Add env template", change).push().join();

        // Start watching
        final CompletableFuture<Entry<JsonNode>> future =
                testRepo1.watch(Query.ofJson("/env.json"))
                         .applyTemplate(true)
                         .start(pushResult.revision());

        assertThat(future).isNotDone();

        // Update the repo-level variable
        updateVariable(TEST_PROJECT, TEST_REPO_1, "env", VariableType.STRING, "production");

        // Watcher should be notified
        final Entry<JsonNode> entry = future.join();
        assertThatJson(entry.content()).isEqualTo("{ \"environment\": \"production\" }");
    }

    @Test
    void watcherShouldBeNotifiedWhenVariableIsCreated() throws InterruptedException {
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", "{ \"config\": \"fixed\" }");
        testRepo1.commit("Add config", change).push().join();

        final Watcher<JsonNode> watcher = testRepo1.watcher(Query.ofJson("/config.json"))
                                                   .applyTemplate(true)
                                                   .start();

        assertThatJson(watcher.awaitInitialValue().value()).isEqualTo(" { \"config\": \"fixed\" } ");

        createVariable(TEST_PROJECT, null, "newConfig", VariableType.STRING, "dynamic-value");

        final Change<JsonNode> change1 = Change.ofJsonUpsert("/config.json",
                                                             "{ \"config\": \"${vars.newConfig}\" }");
        testRepo1.commit("Add config template", change1).push().join();

        await().untilAsserted(() -> {
            assertThatJson(watcher.latestValue()).isEqualTo("{ \"config\": \"dynamic-value\" }");
        });
    }

    @Test
    void watcherShouldBeNotifiedWhenVariableIsDeleted() throws InterruptedException {
        // Create initial variables - both project and repo level
        createVariable(TEST_PROJECT, null, "fallback", VariableType.STRING, "project-value");
        createVariable(TEST_PROJECT, TEST_REPO_1, "fallback", VariableType.STRING, "repo-value");

        final String templateContent = "{ \"value\": \"${vars.fallback}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/fallback.json", templateContent);
        final PushResult pushResult = testRepo1.commit("Add fallback template", change).push().join();

        // Verify repo-level variable is used initially
        final Entry<JsonNode> initial = testRepo1.file(Query.ofJson("/fallback.json"))
                                                 .applyTemplate(true)
                                                 .get()
                                                 .join();
        assertThatJson(initial.content()).isEqualTo("{ \"value\": \"repo-value\" }");

        // Start watching
        final CompletableFuture<Entry<JsonNode>> future =
                testRepo1.watch(Query.ofJson("/fallback.json"))
                         .applyTemplate(true)
                         .start(pushResult.revision());

        assertThat(future).isNotDone();

        // Delete the repo-level variable, should fall back to project-level
        deleteVariable(TEST_PROJECT, TEST_REPO_1, "fallback");

        // Watcher should be notified with project-level value
        final Entry<JsonNode> entry = future.join();
        assertThatJson(entry.content()).isEqualTo("{ \"value\": \"project-value\" }");
    }

    @Test
    void watcherShouldBeNotifiedWhenJsonVariableIsUpdated() throws InterruptedException {
        // Create initial JSON variable
        final String initialJson = "{\"host\":\"old-host.com\",\"port\":8080}";
        createVariable(TEST_PROJECT, null, "server", VariableType.JSON, initialJson);

        final String templateContent = "{ \"url\": \"${vars.server.host}:${vars.server.port}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/server.json", templateContent);
        final PushResult pushResult = testRepo1.commit("Add server template", change).push().join();

        // Start watching
        final CompletableFuture<Entry<JsonNode>> future =
                testRepo1.watch(Query.ofJson("/server.json"))
                         .applyTemplate(true)
                         .start(pushResult.revision());

        assertThat(future).isNotDone();

        // Update the JSON variable
        final String updatedJson = "{\"host\":\"new-host.com\",\"port\":9090}";
        updateVariable(TEST_PROJECT, null, "server", VariableType.JSON, updatedJson);

        // Watcher should be notified with new values
        final Entry<JsonNode> entry = future.join();
        assertThatJson(entry.content()).isEqualTo("{ \"url\": \"new-host.com:9090\" }");
    }

    @Test
    void watcherWithYamlTemplateShouldBeNotifiedWhenVariableIsUpdated() throws InterruptedException {
        createVariable(TEST_PROJECT, null, "appName", VariableType.STRING, "MyApp");
        createVariable(TEST_PROJECT, null, "replicas", VariableType.JSON, "1");

        //language=YAML
        final String templateContent = "name: ${vars.appName}\n" +
                                       "replicas: ${vars.replicas}";
        final Change<JsonNode> change = Change.ofYamlUpsert("/deployment.yaml", templateContent);
        final PushResult pushResult = testRepo1.commit("Add deployment template", change).push().join();

        // Start watching
        final CompletableFuture<Entry<JsonNode>> future =
                testRepo1.watch(Query.ofYaml("/deployment.yaml"))
                         .applyTemplate(true)
                         .start(pushResult.revision());

        assertThat(future).isNotDone();

        // Update the replicas variable
        updateVariable(TEST_PROJECT, null, "replicas", VariableType.JSON, "3");

        // Watcher should be notified
        final Entry<JsonNode> entry = future.join();
        assertThatJson(entry.content()).node("replicas").isEqualTo(3);
        assertThatJson(entry.content()).node("name").isEqualTo("MyApp");
    }

    @Test
    void watcherWithCustomVariableFileShouldBeNotifiedWhenFileIsUpdated() throws InterruptedException {
        // Create custom variable file
        final String customVariables = "{\"theme\": \"light\"}";
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/vars/custom.json", customVariables);
        testRepo1.commit("Add custom variable file", variablesChange).push().join();

        final String templateContent = "{ \"theme\": \"${vars.theme}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/settings.json", templateContent);
        final PushResult pushResult = testRepo1.commit("Add settings template", change).push().join();

        // Start watching with custom variable file
        final CompletableFuture<Entry<JsonNode>> future =
                testRepo1.watch(Query.ofJson("/settings.json"))
                         .applyTemplate("/vars/custom.json")
                         .start(pushResult.revision());

        assertThat(future).isNotDone();

        // Update the custom variable file
        final String updatedVariables = "{\"theme\": \"dark\"}";
        final Change<JsonNode> updateChange = Change.ofJsonUpsert("/vars/custom.json", updatedVariables);
        testRepo1.commit("Update custom variable file", updateChange).push().join();

        // Watcher should be notified
        final Entry<JsonNode> entry = future.join();
        assertThatJson(entry.content()).isEqualTo("{ \"theme\": \"dark\" }");
    }

    @Test
    void watcherWithDefaultVariableFileShouldBeNotifiedWhenFileIsUpdated() throws InterruptedException {
        // Create default variable file
        final String defaultVariables = "{\"color\": \"blue\"}";
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/.variables.json", defaultVariables);
        testRepo1.commit("Add .variables.json", variablesChange).push().join();

        final String templateContent = "{ \"color\": \"${vars.color}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/ui.json", templateContent);
        final PushResult pushResult = testRepo1.commit("Add UI template", change).push().join();

        // Start watching
        final CompletableFuture<Entry<JsonNode>> future =
                testRepo1.watch(Query.ofJson("/ui.json"))
                         .applyTemplate(true)
                         .start(pushResult.revision());

        assertThat(future).isNotDone();

        // Update the default variable file
        final String updatedVariables = "{\"color\": \"red\"}";
        final Change<JsonNode> updateChange = Change.ofJsonUpsert("/.variables.json", updatedVariables);
        testRepo1.commit("Update .variables.json", updateChange).push().join();

        // Watcher should be notified
        final Entry<JsonNode> entry = future.join();
        assertThatJson(entry.content()).isEqualTo("{ \"color\": \"red\" }");
    }

    @Test
    void watcherShouldBeNotifiedMultipleTimesWhenVariableIsUpdatedMultipleTimes()
            throws InterruptedException {
        createVariable(TEST_PROJECT, null, "counter", VariableType.STRING, "0");

        final String templateContent = "{ \"count\": \"${vars.counter}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/counter.json", templateContent);
        final PushResult pushResult = testRepo1.commit("Add counter template", change).push().join();

        // First watch
        CompletableFuture<Entry<JsonNode>> future =
                testRepo1.watch(Query.ofJson("/counter.json"))
                         .applyTemplate(true)
                         .start(pushResult.revision());

        assertThat(future).isNotDone();

        updateVariable(TEST_PROJECT, null, "counter", VariableType.STRING, "1");
        Entry<JsonNode> entry = future.join();
        assertThatJson(entry.content()).node("count").isEqualTo("\"1\"");

        // Second watch from the previous result
        future = testRepo1.watch(Query.ofJson("/counter.json"))
                          .applyTemplate(true)
                          .start(entry.revision());

        Thread.sleep(100);
        assertThat(future).isNotDone();

        // Second update
        updateVariable(TEST_PROJECT, null, "counter", VariableType.STRING, "2");
        entry = future.join();
        assertThatJson(entry.content()).node("count").isEqualTo("\"2\"");
    }

    @Test
    void watcherShouldNotBeNotifiedWhenUnrelatedVariableIsUpdated() throws InterruptedException {
        createVariable(TEST_PROJECT, null, "usedVar", VariableType.STRING, "used");
        createVariable(TEST_PROJECT, null, "unusedVar", VariableType.STRING, "unused");

        final String templateContent = "{ \"value\": \"${vars.usedVar}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/test.json", templateContent);
        final PushResult pushResult = testRepo1.commit("Add test template", change).push().join();

        // Start watching
        final CompletableFuture<Entry<JsonNode>> future =
                testRepo1.watch(Query.ofJson("/test.json"))
                         .applyTemplate(true)
                         .start(pushResult.revision());

        Thread.sleep(500);
        assertThat(future).isNotDone();

        // Update an unrelated variable
        updateVariable(TEST_PROJECT, null, "unusedVar", VariableType.STRING, "still-unused");

        // Wait a bit and verify watcher is not notified
        Thread.sleep(1000);
        assertThat(future).isNotDone();

        // Now update the used variable
        updateVariable(TEST_PROJECT, null, "usedVar", VariableType.STRING, "updated");

        // Watcher should now be notified
        final Entry<JsonNode> entry = future.join();
        assertThatJson(entry.content()).isEqualTo("{ \"value\": \"updated\" }");
    }

    @Test
    void watcherWithTextTemplateShouldBeNotifiedWhenVariableIsUpdated() throws InterruptedException {
        createVariable(TEST_PROJECT, null, "message", VariableType.STRING, "Hello");

        final String templateContent = "Message: ${vars.message}";
        final Change<String> change = Change.ofTextUpsert("/message.txt", templateContent);
        final PushResult pushResult = testRepo1.commit("Add message template", change).push().join();

        final CompletableFuture<Entry<String>> future =
                testRepo1.watch(Query.ofText("/message.txt"))
                         .applyTemplate(true)
                         .start(pushResult.revision());

        Thread.sleep(500);
        assertThat(future).isNotDone();

        updateVariable(TEST_PROJECT, null, "message", VariableType.STRING, "World");

        final Entry<String> entry = future.join();
        assertThat(entry.content()).isEqualTo("Message: World\n");
    }

    @Test
    void watcherOnMultipleReposShouldBeNotifiedIndependently() throws InterruptedException {
        createVariable(TEST_PROJECT, TEST_REPO_1, "repoName", VariableType.STRING, "repo1");
        createVariable(TEST_PROJECT, TEST_REPO_2, "repoName", VariableType.STRING, "repo2");

        final String templateContent = "{ \"repo\": \"${vars.repoName}\" }";

        final Change<JsonNode> change1 = Change.ofJsonUpsert("/info.json", templateContent);
        final PushResult pushResult1 = testRepo1.commit("Add info template", change1).push().join();
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/info.json", templateContent);
        final PushResult pushResult2 = testRepo2.commit("Add info template", change2).push().join();

        final CompletableFuture<Entry<JsonNode>> future1 =
                testRepo1.watch(Query.ofJson("/info.json"))
                         .applyTemplate(true)
                         .start(pushResult1.revision());

        final CompletableFuture<Entry<JsonNode>> future2 =
                testRepo2.watch(Query.ofJson("/info.json"))
                         .applyTemplate(true)
                         .start(pushResult2.revision());

        Thread.sleep(500);
        assertThat(future1).isNotDone();
        assertThat(future2).isNotDone();

        // Update only repo1's variable
        updateVariable(TEST_PROJECT, TEST_REPO_1, "repoName", VariableType.STRING, "repo1-updated");

        // Only repo1's watcher should be notified
        final Entry<JsonNode> entry1 = future1.join();
        assertThatJson(entry1.content()).isEqualTo("{ \"repo\": \"repo1-updated\" }");

        // repo2's watcher should still be waiting
        Thread.sleep(500);
        assertThat(future2).isNotDone();

        // Now update repo2's variable
        updateVariable(TEST_PROJECT, TEST_REPO_2, "repoName", VariableType.STRING, "repo2-updated");

        // repo2's watcher should now be notified
        final Entry<JsonNode> entry2 = future2.join();
        assertThatJson(entry2.content()).isEqualTo("{ \"repo\": \"repo2-updated\" }");
    }

    @Test
    void longRunningWatcherShouldReceiveUpdatesWhenProjectVariableChanges() throws InterruptedException {
        createVariable(TEST_PROJECT, null, "status", VariableType.STRING, "starting");

        final String templateContent = "{ \"status\": \"${vars.status}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/status.json", templateContent);
        testRepo1.commit("Add status template", change).push().join();

        // Create a long-running watcher
        try (Watcher<JsonNode> watcher = testRepo1.watcher(Query.ofJson("/status.json"))
                                                  .applyTemplate(true)
                                                  .start()) {
            assertThatJson(watcher.awaitInitialValue().value()).isEqualTo("{ \"status\": \"starting\" }");
            updateVariable(TEST_PROJECT, null, "status", VariableType.STRING, "running");
            // Verify the watcher receives the update
            await().untilAsserted(() -> {
                assertThatJson(watcher.latestValue()).isEqualTo("{ \"status\": \"running\" }");
            });

            updateVariable(TEST_PROJECT, null, "status", VariableType.STRING, "stopped");
            await().untilAsserted(() -> {
                assertThatJson(watcher.latestValue()).isEqualTo("{ \"status\": \"stopped\" }");
            });
        }
    }

    @Test
    void longRunningWatcherShouldReceiveUpdatesWhenRepoVariableChanges() throws InterruptedException {
        createVariable(TEST_PROJECT, TEST_REPO_1, "dbHost", VariableType.STRING, "localhost");

        final String templateContent = "{ \"host\": \"${vars.dbHost}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/db.json", templateContent);
        testRepo1.commit("Add db template", change).push().join();

        try (Watcher<JsonNode> watcher = testRepo1.watcher(Query.ofJson("/db.json"))
                                                  .applyTemplate(true)
                                                  .start()) {
            assertThatJson(watcher.awaitInitialValue().value()).isEqualTo("{ \"host\": \"localhost\" }");

            // Update repo variable
            updateVariable(TEST_PROJECT, TEST_REPO_1, "dbHost", VariableType.STRING, "db.production.com");

            await().untilAsserted(() -> {
                assertThatJson(watcher.latestValue()).isEqualTo("{ \"host\": \"db.production.com\" }");
            });
        }
    }

    @Test
    void longRunningWatcherWithYamlShouldReceiveUpdatesWhenVariableChanges() throws InterruptedException {
        createVariable(TEST_PROJECT, null, "appVersion", VariableType.STRING, "1.0.0");
        createVariable(TEST_PROJECT, null, "replicas", VariableType.JSON, "1");

        //language=YAML
        final String templateContent = "version: ${vars.appVersion}\n" +
                                       "replicas: ${vars.replicas}";
        final Change<JsonNode> change = Change.ofYamlUpsert("/app.yaml", templateContent);
        testRepo1.commit("Add app template", change).push().join();

        try (Watcher<JsonNode> watcher = testRepo1.watcher(Query.ofYaml("/app.yaml"))
                                                  .applyTemplate(true)
                                                  .start()) {
            final JsonNode initial = watcher.awaitInitialValue().value();
            assertThatJson(initial).node("version").isEqualTo("1.0.0");
            assertThatJson(initial).node("replicas").isEqualTo(1);

            updateVariable(TEST_PROJECT, null, "replicas", VariableType.JSON, "5");

            await().untilAsserted(() -> {
                assertThatJson(watcher.latestValue()).node("replicas").isEqualTo(5);
            });

            updateVariable(TEST_PROJECT, null, "appVersion", VariableType.STRING, "2.0.0");
            await().untilAsserted(() -> {
                assertThatJson(watcher.latestValue()).node("version").isEqualTo("2.0.0");
            });
        }
    }

    @Test
    void longRunningWatcherWithCustomVariableFileShouldReceiveUpdates() throws InterruptedException {
        final String customVariables = "{\"env\": \"dev\"}";
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/vars/env.json", customVariables);
        testRepo1.commit("Add custom variable file", variablesChange).push().join();

        final String templateContent = "{ \"environment\": \"${vars.env}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        try (Watcher<JsonNode> watcher = testRepo1.watcher(Query.ofJson("/config.json"))
                                                  .applyTemplate("/vars/env.json")
                                                  .start()) {
            assertThatJson(watcher.awaitInitialValue().value()).isEqualTo("{ \"environment\": \"dev\" }");

            final String updatedVariables = "{\"env\": \"prod\"}";
            final Change<JsonNode> updateChange = Change.ofJsonUpsert("/vars/env.json", updatedVariables);
            testRepo1.commit("Update custom variable file", updateChange).push().join();

            await().untilAsserted(() -> {
                assertThatJson(watcher.latestValue()).isEqualTo("{ \"environment\": \"prod\" }");
            });
        }
    }

    @Test
    void longRunningWatcherShouldReceiveUpdatesWhenDefaultVariableFileChanges() throws InterruptedException {
        final String defaultVariables = "{\"theme\": \"light\"}";
        final Change<JsonNode> variablesChange = Change.ofJsonUpsert("/.variables.json", defaultVariables);
        testRepo1.commit("Add .variables.json", variablesChange).push().join();

        final String templateContent = "{ \"theme\": \"${vars.theme}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/ui-settings.json", templateContent);
        testRepo1.commit("Add UI settings template", change).push().join();

        try (Watcher<JsonNode> watcher = testRepo1.watcher(Query.ofJson("/ui-settings.json"))
                                                  .applyTemplate(true)
                                                  .start()) {
            assertThatJson(watcher.awaitInitialValue().value()).isEqualTo("{ \"theme\": \"light\" }");

            final String updatedVariables = "{\"theme\": \"dark\"}";
            final Change<JsonNode> updateChange = Change.ofJsonUpsert("/.variables.json", updatedVariables);
            testRepo1.commit("Update .variables.json", updateChange).push().join();

            await().untilAsserted(() -> {
                assertThatJson(watcher.latestValue()).isEqualTo("{ \"theme\": \"dark\" }");
            });
        }
    }

    @Test
    void longRunningWatcherWithJsonPathShouldReceiveUpdatesWhenVariableChanges() throws InterruptedException {
        createVariable(TEST_PROJECT, null, "port", VariableType.STRING, "8080");

        final String templateContent = "{ \"server\": { \"port\": \"${vars.port}\" } }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/server-config.json", templateContent);
        testRepo1.commit("Add server config template", change).push().join();

        // Use JSON path to watch only the port
        try (Watcher<JsonNode> watcher =
                     testRepo1.watcher(Query.ofJsonPath("/server-config.json", "$.server.port"))
                              .applyTemplate(true)
                              .start()) {
            assertThatJson(watcher.awaitInitialValue().value()).isEqualTo("\"8080\"");

            // Update the port variable
            updateVariable(TEST_PROJECT, null, "port", VariableType.STRING, "9090");

            await().untilAsserted(() -> {
                assertThatJson(watcher.latestValue()).isEqualTo("\"9090\"");
            });
        }
    }

    private void createVariable(String projectName, @Nullable String repoName, String id,
                                VariableType type, String value) {
        final Variable variable = new Variable(id, type, null, value);
        final String path;
        if (repoName == null) {
            path = "/api/v1/projects/" + projectName + "/variables";
        } else {
            path = "/api/v1/projects/" + projectName + "/repos/" + repoName + "/variables";
        }

        final AggregatedHttpResponse response = httpClient.prepare()
                                                          .post(path)
                                                          .contentJson(variable)
                                                          .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    }

    private void updateVariable(String projectName, @Nullable String repoName, String id,
                                VariableType type, String value) {
        final Variable variable = new Variable(id, type, null, value);
        final String path;
        if (repoName == null) {
            path = "/api/v1/projects/" + projectName + "/variables/" + id;
        } else {
            path = "/api/v1/projects/" + projectName + "/repos/" + repoName + "/variables/" + id;
        }

        final AggregatedHttpResponse response = httpClient.prepare()
                                                          .put(path)
                                                          .contentJson(variable)
                                                          .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    private void deleteVariable(String projectName, @Nullable String repoName, String id) {
        final String path;
        if (repoName == null) {
            path = "/api/v1/projects/" + projectName + "/variables/" + id;
        } else {
            path = "/api/v1/projects/" + projectName + "/repos/" + repoName + "/variables/" + id;
        }

        final AggregatedHttpResponse response = httpClient.prepare()
                                                          .delete(path)
                                                          .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
