package com.linecorp.centraldogma.client;

import static java.util.Objects.requireNonNull;

import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Revision;

public final class ImportResult {

    public static ImportResult fromPushResult(PushResult pr) {
        requireNonNull(pr, "pushResult");
        return new ImportResult(pr.revision(), pr.when());
    }

    public static ImportResult empty() {
        return new ImportResult(Revision.INIT, 0, true);
    }

    private final boolean isEmpty;
    private final Revision revision;
    private final long when;

    private ImportResult(Revision revision, long when, boolean isEmpty) {
        this.revision = requireNonNull(revision, "revision");
        this.when = when / 1000L * 1000L;
        this.isEmpty = isEmpty;
    }

    private ImportResult(Revision revision, long when) {
        this(revision, when, false);
    }

    public boolean isEmpty() {
        return isEmpty;
    }

    public Revision revision() {
        return revision;
    }

    public long whenMillis() {
        return when;
    }

    @Override
    public String toString() {
        return "ImportResult{" +
               "isEmpty=" + isEmpty +
               ", revision=" + revision +
               ", when=" + when +
               '}';
    }
}
