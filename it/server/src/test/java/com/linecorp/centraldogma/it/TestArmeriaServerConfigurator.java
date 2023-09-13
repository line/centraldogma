package com.linecorp.centraldogma.it;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.centraldogma.server.ArmeriaServerConfigurator;

public final class TestArmeriaServerConfigurator implements ArmeriaServerConfigurator {

    @Override
    public void configure(ServerBuilder serverBuilder) {
        serverBuilder.service("/hello", (ctx, req) -> HttpResponse.of("Hello, world!"));
    }
}
