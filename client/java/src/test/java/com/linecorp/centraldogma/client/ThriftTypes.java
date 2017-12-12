/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.centraldogma.client;

import java.util.List;

import com.linecorp.centraldogma.internal.thrift.Author;
import com.linecorp.centraldogma.internal.thrift.Change;
import com.linecorp.centraldogma.internal.thrift.ChangeType;
import com.linecorp.centraldogma.internal.thrift.Comment;
import com.linecorp.centraldogma.internal.thrift.Commit;
import com.linecorp.centraldogma.internal.thrift.Entry;
import com.linecorp.centraldogma.internal.thrift.EntryType;
import com.linecorp.centraldogma.internal.thrift.Markup;
import com.linecorp.centraldogma.internal.thrift.Revision;

/**
 * Provides the subclasses of Thrift-generated structs and the aliases of Thrift-generated enums to avoid
 * namespace clashes between the model classes in the 'common' package.
 */
final class ThriftTypes {

    static final class TEntryType {
        static final EntryType TEXT = EntryType.TEXT;

        private TEntryType() {}
    }

    static final class TMarkup {
        static final Markup PLAINTEXT = Markup.PLAINTEXT;

        private TMarkup() {}
    }

    static class TCommit extends Commit {
        private static final long serialVersionUID = 847711751484218046L;

        TCommit(Revision revision, Author author, String timestamp, String summary, Comment detail,
                List<Change> diffs) {
            super(revision, author, timestamp, summary, detail, diffs);
        }
    }

    static class TRevision extends Revision {
        private static final long serialVersionUID = -4549948907219772242L;

        TRevision(int major) {
            super(major, 0);
        }
    }

    static class TAuthor extends Author {
        private static final long serialVersionUID = 7499619843833196989L;

        TAuthor(String name, String email) {
            super(name, email);
        }
    }

    static class TEntry extends Entry {
        private static final long serialVersionUID = 3394357378752556077L;

        TEntry(String path, EntryType type) {
            super(path, type);
        }
    }

    static class TChange extends Change {
        private static final long serialVersionUID = -6731430884846151634L;

        TChange(String path, ChangeType type) {
            super(path, type);
        }
    }

    private ThriftTypes() {}
}
