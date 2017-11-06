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

package com.linecorp.centraldogma.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

final class DeletingFileVisitor extends SimpleFileVisitor<Path> {

    static final FileVisitor<Path> INSTANCE = new DeletingFileVisitor();

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        delete(file);
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
        delete(file);
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
        delete(dir);

        if (e == null) {
            return FileVisitResult.CONTINUE;
        } else {
            throw e;
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored") // Best effort only
    private static void delete(Path p) {
        final File f = p.toFile();
        f.delete();
        f.setWritable(true, true);
    }
}
