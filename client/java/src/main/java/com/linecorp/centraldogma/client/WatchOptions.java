package com.linecorp.centraldogma.client;

public final class WatchOptions {
    private final long timeoutMillis;
    private final boolean errorOnEntryNotFound;

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public boolean isErrorOnEntryNotFound() {
        return errorOnEntryNotFound;
    }

    private WatchOptions(long timeoutMillis, boolean errorOnEntryNotFound) {
        this.timeoutMillis = timeoutMillis;
        this.errorOnEntryNotFound = errorOnEntryNotFound;
    }

    public static WatchOptions defaultOptions() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long timeoutMillis = WatchConstants.DEFAULT_WATCH_TIMEOUT_MILLIS;
        private boolean errorOnEntryNotFound = WatchConstants.DEFAULT_WATCH_ERROR_ON_ENTRY_NOT_FOUND;

        public Builder timeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public Builder errorOnEntryNotFound(boolean errorOnEntryNotFound) {
            this.errorOnEntryNotFound = errorOnEntryNotFound;
            return this;
        }

        public WatchOptions build() {
            return new WatchOptions(timeoutMillis, errorOnEntryNotFound);
        }
    }
}
