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
        //todo: should refactor this logic
        return new ImportResult(Revision.INIT, 0);
    }

    private final Revision revision;
    private final long when;

    private ImportResult(Revision revision, long when) {
        this.revision = requireNonNull(revision, "revision");
        this.when = when / 1000L * 1000L; // Drop the milliseconds
    }

    public Revision revision() {return revision;}

    public long whenMillis() {return when;}
}
