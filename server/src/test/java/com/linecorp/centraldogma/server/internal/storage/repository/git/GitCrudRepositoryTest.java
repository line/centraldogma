/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Objects;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.CrudRepository;
import com.linecorp.centraldogma.server.internal.storage.repository.HasId;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;
import com.linecorp.centraldogma.testing.internal.CrudRepositoryExtension;

class GitCrudRepositoryTest {

    private static final String TEST_PROJ = "test-proj";
    private static final String TEST_REPO = "test-repo";

    @RegisterExtension
    static CrudRepositoryExtension<Foo> repositoryExtension =
            new CrudRepositoryExtension<>(Foo.class, TEST_PROJ, TEST_REPO, "/test-storage/");

    @Test
    void crudTest() {
        final CrudRepository<Foo> repository = repositoryExtension.crudRepository();
        // Create
        final Foo data1 = new Foo("id_1", "test1", 100);
        final Foo data1Saved = repository.save(data1, Author.DEFAULT).join().object();
        assertThat(data1Saved).isEqualTo(data1);

        // Read
        final Foo data1Found = repository.find(data1.id()).join().object();
        assertThat(data1Found).isEqualTo(data1Saved);
        assertThat(repository.find("id_unknown").join()).isNull();

        // Update
        final Foo data1Updated = new Foo("id_1", "test2", 100);
        final Foo data1UpdatedFound = repository.update(data1Updated, Author.DEFAULT).join().object();
        assertThat(data1UpdatedFound).isEqualTo(data1Updated);
        assertThatThrownBy(() -> {
            repository.update(new Foo("id_2", "test2", 100), Author.DEFAULT).join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(EntryNotFoundException.class)
          .hasMessageContaining("Cannot update a non-existent entity. (ID: id_2)");

        // Create again
        final Foo data3 = new Foo("id_3", "test3", 100);
        repository.save(data3, Author.DEFAULT).join();
        final Foo data3Found = repository.find(data3.id()).join().object();
        assertThat(data3Found).isEqualTo(data3);
        // Make sure the previous data is not affected.
        assertThat(repository.find(data1.id()).join().object()).isEqualTo(data1Updated);

        // Read all
        assertThat(repository.findAll().join().stream().map(HasRevision::object))
                .containsExactly(data1Updated, data3);

        // Delete
        repository.delete(data1.id(), Author.DEFAULT).join();
        assertThat(repository.findAll().join().stream().map(HasRevision::object))
                .containsExactly(data3);
        assertThat(repository.find(data1.id()).join()).isNull();

        // Reuse the deleted ID
        assertThat(repository.save(data1, Author.DEFAULT).join().object()).isEqualTo(data1);
        assertThat(repository.find(data1.id()).join().object()).isEqualTo(data1);
        assertThat(repository.findAll().join().stream().map(HasRevision::object))
                .containsExactly(data1, data3);
    }

    private static class Foo implements HasId<Foo> {
        private final String id;
        private final String bar;
        private final int baz;

        @JsonCreator
        Foo(@JsonProperty("id") String id, @JsonProperty("bar") String bar, @JsonProperty("baz") int baz) {
            this.id = id;
            this.bar = bar;
            this.baz = baz;
        }

        @JsonProperty
        @Override
        public String id() {
            return id;
        }

        @JsonProperty
        public String bar() {
            return bar;
        }

        @JsonProperty
        public int baz() {
            return baz;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Foo)) {
                return false;
            }
            final Foo foo = (Foo) o;
            return baz == foo.baz && Objects.equals(id, foo.id) && Objects.equals(bar, foo.bar);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, bar, baz);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("id", id)
                              .add("bar", bar)
                              .add("baz", baz)
                              .toString();
        }
    }
}
