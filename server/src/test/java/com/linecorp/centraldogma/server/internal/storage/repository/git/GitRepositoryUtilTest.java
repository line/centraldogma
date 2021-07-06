/*
 * Copyright 2021 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.server.storage.StorageException;

final class GitRepositoryUtilTest {

    @Test
    void testDoUpdateRef() throws Exception {
        final ObjectId commitId = mock(ObjectId.class);

        // A commit on the mainlane
        testDoUpdateRef(Constants.R_TAGS + '1', commitId, false);
        testDoUpdateRef(Constants.R_HEADS + Constants.MASTER, commitId, false);
    }

    private static void testDoUpdateRef(String ref, ObjectId commitId, boolean tagExists) throws Exception {
        final org.eclipse.jgit.lib.Repository jGitRepo = mock(org.eclipse.jgit.lib.Repository.class);
        final RevWalk revWalk = mock(RevWalk.class);
        final RefUpdate refUpdate = mock(RefUpdate.class);

        lenient().when(jGitRepo.exactRef(ref)).thenReturn(tagExists ? mock(Ref.class) : null);
        lenient().when(jGitRepo.updateRef(ref)).thenReturn(refUpdate);

        lenient().when(refUpdate.update(revWalk)).thenReturn(RefUpdate.Result.NEW);
        GitRepositoryUtil.doRefUpdate(jGitRepo, revWalk, ref, commitId);

        when(refUpdate.update(revWalk)).thenReturn(RefUpdate.Result.FAST_FORWARD);
        GitRepositoryUtil.doRefUpdate(jGitRepo, revWalk, ref, commitId);

        when(refUpdate.update(revWalk)).thenReturn(RefUpdate.Result.LOCK_FAILURE);
        assertThatThrownBy(() -> GitRepositoryUtil.doRefUpdate(jGitRepo, revWalk, ref, commitId))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void testDoUpdateRefOnExistingTag() {
        final ObjectId commitId = mock(ObjectId.class);

        assertThatThrownBy(() -> testDoUpdateRef(Constants.R_TAGS + "01/1.0", commitId, true))
                .isInstanceOf(StorageException.class);
    }
}
