package com.linecorp.centraldogma.server.internal.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;
import com.linecorp.centraldogma.testing.junit4.CentralDogmaRule;
import org.junit.ClassRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

class RepositoryServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        }
    };

    @Test
    void getUsersInfo() throws IOException {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse userInfo = client.get("/api/v0/users/me").aggregate().join();
        final JsonNode jsonNode = Jackson.readTree(userInfo.contentUtf8());
        assertThat(jsonNode.get("login").asText()).isEqualTo("admin@localhost.localdomain");
    }
}
