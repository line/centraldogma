package com.linecorp.centraldogma.internal.api.v1;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class AccessToken {

    private static final String BEARER = "Bearer";

    private final String accessToken;

    private final String tokenType = BEARER;

    private final long expiresIn;

    private final String refreshToken;

    //TODO(minwoox) add scope when it needs

    public AccessToken(String accessToken) {
        this(accessToken, 0);
    }

    public AccessToken(String accessToken, long expiresIn) {
        this(accessToken, expiresIn, "");
    }

    @JsonCreator
    public AccessToken(@JsonProperty("access_token") String accessToken,
                       @JsonProperty("expires_in") long expiresIn,
                       @JsonProperty("refresh_token") String refreshToken) {
        this.accessToken = requireNonNull(accessToken, "accessToken");
        this.expiresIn = expiresIn;
        this.refreshToken = requireNonNull(refreshToken, "refreshToken");
    }

    @JsonProperty("access_token")
    public String accessToken() {
        return accessToken;
    }

    @JsonProperty("expires_in")
    public long expiresIn() {
        return expiresIn;
    }

    @JsonProperty("refresh_token")
    public String refreshToken() {
        return refreshToken;
    }
}
