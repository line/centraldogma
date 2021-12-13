package com.linecorp.centraldogma.client;

@FunctionalInterface
public interface WatcherRetryRule {
    Boolean shouldRetry(Throwable cause);
}
