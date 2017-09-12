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

package com.linecorp.centraldogma.server.internal.thrift;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.linecorp.centraldogma.common.QueryException;
import com.linecorp.centraldogma.internal.thrift.Author;
import com.linecorp.centraldogma.internal.thrift.AuthorConverter;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaException;
import com.linecorp.centraldogma.internal.thrift.Change;
import com.linecorp.centraldogma.internal.thrift.ChangeConverter;
import com.linecorp.centraldogma.internal.thrift.ChangeType;
import com.linecorp.centraldogma.internal.thrift.Commit;
import com.linecorp.centraldogma.internal.thrift.CommitConverter;
import com.linecorp.centraldogma.internal.thrift.Entry;
import com.linecorp.centraldogma.internal.thrift.EntryConverter;
import com.linecorp.centraldogma.internal.thrift.EntryType;
import com.linecorp.centraldogma.internal.thrift.ErrorCode;
import com.linecorp.centraldogma.internal.thrift.Markup;
import com.linecorp.centraldogma.internal.thrift.MarkupConverter;
import com.linecorp.centraldogma.internal.thrift.Project;
import com.linecorp.centraldogma.internal.thrift.Query;
import com.linecorp.centraldogma.internal.thrift.QueryConverter;
import com.linecorp.centraldogma.internal.thrift.Repository;
import com.linecorp.centraldogma.internal.thrift.Revision;
import com.linecorp.centraldogma.internal.thrift.RevisionConverter;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectExistsException;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.ChangeConflictException;
import com.linecorp.centraldogma.server.internal.storage.repository.EntryNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.RedundantChangeException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryExistsException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.RevisionExistsException;
import com.linecorp.centraldogma.server.internal.storage.repository.RevisionNotFoundException;

final class Converter {

    static <T, U> List<T> convert(Collection<U> list, Function<U, T> mapper) {
        if (list == null) {
            return null;
        }
        return list.stream().map(mapper).collect(Collectors.toList());
    }

    ////////////////

    ////// Revision
    static Revision convert(com.linecorp.centraldogma.common.Revision rev) {
        return RevisionConverter.TO_DATA.convert(rev);
    }

    static com.linecorp.centraldogma.common.Revision convert(Revision rev) {
        return RevisionConverter.TO_MODEL.convert(rev);
    }
    ////////////////

    ////// Author
    static Author convert(com.linecorp.centraldogma.common.Author author) {
        return AuthorConverter.TO_DATA.convert(author);
    }

    static com.linecorp.centraldogma.common.Author convert(Author author) {
        return AuthorConverter.TO_MODEL.convert(author);
    }
    ////////////////

    ////// Change
    static com.linecorp.centraldogma.common.Change<?> convert(Change c) {
        return ChangeConverter.TO_MODEL.convert(c);
    }

    static Change convert(com.linecorp.centraldogma.common.Change<?> c) {
        return ChangeConverter.TO_DATA.convert(c);
    }
    ////////////////

    ////// Query
    @SuppressWarnings("unchecked")
    static <T> com.linecorp.centraldogma.common.Query<T> convert(Query query) {
        return (com.linecorp.centraldogma.common.Query<T>) QueryConverter.TO_MODEL.convert(query);
    }

    static Query convert(com.linecorp.centraldogma.common.Query<?> query) {
        return QueryConverter.TO_DATA.convert(query);
    }

    ////////////////

    ////// Markup
    static Markup convert(com.linecorp.centraldogma.common.Markup markup) {
        return MarkupConverter.TO_DATA.convert(markup);
    }

    static com.linecorp.centraldogma.common.Markup convert(Markup markup) {
        return MarkupConverter.TO_MODEL.convert(markup);
    }
    ////////////////

    ////// EntryType
    static EntryType convert(com.linecorp.centraldogma.common.EntryType type) {
        return EntryConverter.convertEntryType(type);
    }
    ////////////////

    ////// ChangeType
    static ChangeType convert(com.linecorp.centraldogma.common.ChangeType type) {
        if (type == null) {
            return null;
        }

        return ChangeType.valueOf(type.name());
    }
    ////////////////

    static Entry convert(com.linecorp.centraldogma.common.Entry<?> entry) {
        return EntryConverter.TO_DATA.convert(entry);
    }

    // The parameter 'project' is not used at the moment, but will be used once schema and plugin support lands.
    static Project convert(
            String name, com.linecorp.centraldogma.server.internal.storage.project.Project project) {
        return new Project(name);
    }

    static CompletableFuture<Repository> convert(
            String name, com.linecorp.centraldogma.server.internal.storage.repository.Repository repo) {

        return repo.history(
                com.linecorp.centraldogma.common.Revision.HEAD,
                com.linecorp.centraldogma.common.Revision.HEAD,
                com.linecorp.centraldogma.server.internal.storage.repository.Repository.ALL_PATH, 1).thenApply(
                        history -> new Repository(name).setHead(convert(history.get(0))));
    }

    static Commit convert(com.linecorp.centraldogma.common.Commit commit) {
        return CommitConverter.TO_DATA.convert(commit);
    }

    static com.linecorp.centraldogma.common.Commit convert(Commit commit) {
        return CommitConverter.TO_MODEL.convert(commit);
    }

    ////// CentralDogmaException
    static CentralDogmaException convert(Throwable t) {
        if ((t instanceof InvocationTargetException ||
             t instanceof CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }

        if (t instanceof CentralDogmaException) {
            return (CentralDogmaException) t;
        }

        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        if (t instanceof IllegalArgumentException) {
            code = ErrorCode.BAD_REQUEST;
        } else if (t instanceof EntryNotFoundException) {
            code = ErrorCode.ENTRY_NOT_FOUND;
        } else if (t instanceof RevisionNotFoundException) {
            code = ErrorCode.REVISION_NOT_FOUND;
        } else if (t instanceof RevisionExistsException) {
            code = ErrorCode.REVISION_EXISTS;
        } else if (t instanceof QueryException) {
            code = ErrorCode.QUERY_FAILURE;
        } else if (t instanceof RedundantChangeException) {
            code = ErrorCode.REDUNDANT_CHANGE;
        } else if (t instanceof ChangeConflictException) {
            code = ErrorCode.CHANGE_CONFLICT;
        } else if (t instanceof ProjectNotFoundException) {
            code = ErrorCode.PROJECT_NOT_FOUND;
        } else if (t instanceof ProjectExistsException) {
            code = ErrorCode.PROJECT_EXISTS;
        } else if (t instanceof RepositoryNotFoundException) {
            code = ErrorCode.REPOSITORY_NOT_FOUND;
        } else if (t instanceof RepositoryExistsException) {
            code = ErrorCode.REPOSITORY_EXISTS;
        }

        final CentralDogmaException cde = new CentralDogmaException(code);
        cde.setMessage(t.toString());
        cde.initCause(t);
        return cde;
    }

    private Converter() {}
}
