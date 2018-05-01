package com.linecorp.centraldogma.client.armeria;

import static com.linecorp.centraldogma.client.armeria.JsonEndpointListDecoderTest.ENDPOINT_LIST;
import static com.linecorp.centraldogma.client.armeria.JsonEndpointListDecoderTest.HOST_AND_PORT_LIST;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;

public class TextEndpointListDecoderTest {
    @Test
    public void decode() {
        EndpointListDecoder<String> decoder = EndpointListDecoder.TEXT;
        List<Endpoint> decoded = decoder.decode(HOST_AND_PORT_LIST.stream().collect(Collectors.joining("\n")));

        assertThat(decoded).hasSize(4);
        assertThat(decoded).isEqualTo(ENDPOINT_LIST);
    }
}