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

package com.linecorp.centraldogma.it;

import static com.linecorp.centraldogma.internal.thrift.ErrorCode.ENTRY_NOT_FOUND;
import static com.linecorp.centraldogma.internal.thrift.ErrorCode.PROJECT_NOT_FOUND;
import static com.linecorp.centraldogma.internal.thrift.ErrorCode.QUERY_FAILURE;
import static com.linecorp.centraldogma.internal.thrift.ErrorCode.REPOSITORY_NOT_FOUND;
import static com.linecorp.centraldogma.testing.internal.ExpectedExceptionAppender.assertThatThrownByWithExpectedException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.apache.thrift.TException;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaException;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.EntryNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryNotFoundException;

public class GetFileTest {

    @ClassRule
    public static final CentralDogmaRuleWithScaffolding rule = new CentralDogmaRuleWithScaffolding();

    @Test
    public void testInvalidJsonPath() throws TException {
        assertThatThrownBy(() -> rule.client().getFile(
                rule.project(), rule.repo1(), Revision.HEAD,
                Query.ofJsonPath("/test/test2.json", "$.non_exist_path")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == QUERY_FAILURE);
    }

    @Test
    public void testInvalidFile() throws Exception {
        assertThatThrownByWithExpectedException(EntryNotFoundException.class, "non_existing_file", () ->
                rule.client().getFile(rule.project(), rule.repo1(), Revision.HEAD,
                                      Query.ofJsonPath("/test/non_existing_file.json", "$.a")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == ENTRY_NOT_FOUND);
    }

    @Test
    public void testInvalidRepo() throws Exception {
        assertThatThrownByWithExpectedException(RepositoryNotFoundException.class, "non_exist_repo", () ->
                rule.client().getFile(rule.project(), "non_exist_repo", Revision.HEAD,
                                      Query.ofJsonPath("/test/test2.json", "$.a")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == REPOSITORY_NOT_FOUND);
    }

    @Test
    public void testInvalidProject() throws Exception {
        assertThatThrownByWithExpectedException(ProjectNotFoundException.class, "non_exist_proj", () ->
                rule.client().getFile("non_exist_proj", rule.repo1(), Revision.HEAD,
                                      Query.ofJsonPath("/test/test2.json", "$.non_exist_path")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == PROJECT_NOT_FOUND);
    }
}
