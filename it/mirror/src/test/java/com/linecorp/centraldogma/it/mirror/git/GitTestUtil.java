/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.it.mirror.git;

import java.io.IOException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

public final class GitTestUtil {

    /**
     * Commits the given mirror or credential {@code changes} directly to the meta repository of
     * {@code projectName}, bypassing the push API. Mirror and credential files can no longer be written
     * through the push API (see {@code ContentServiceV1.checkMetaRepoPush}), so tests that need to inject
     * such files (e.g. legacy or otherwise invalid configurations) commit them at the storage layer.
     */
    public static void commitToMetaRepo(CentralDogmaExtension dogma, String projectName,
                                        String summary, Change<?>... changes) {
        dogma.projectManager().get(projectName).metaRepo()
             .commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM, summary, changes)
             .join();
    }

    public static byte @Nullable [] getFileContent(Git git, ObjectId commitId, String fileName)
            throws IOException {
        try (ObjectReader reader = git.getRepository().newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = new RevWalk(reader)) {
            treeWalk.addTree(revWalk.parseTree(commitId).getId());

            while (treeWalk.next()) {
                if (treeWalk.getFileMode() == FileMode.TREE) {
                    treeWalk.enterSubtree();
                    continue;
                }
                if (fileName.equals('/' + treeWalk.getPathString())) {
                    final ObjectId objectId = treeWalk.getObjectId(0);
                    return reader.open(objectId).getBytes();
                }
            }
        }
        return null;
    }

    private GitTestUtil() {}
}
