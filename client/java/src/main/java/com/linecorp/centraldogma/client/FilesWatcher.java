///*
// * Copyright 2021 LINE Corporation
// *
// * LINE Corporation licenses this file to you under the Apache License,
// * version 2.0 (the "License"); you may not use this file except in compliance
// * with the License. You may obtain a copy of the License at:
// *
// *   https://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// * License for the specific language governing permissions and limitations
// * under the License.
// */
//package com.linecorp.centraldogma.client;
//
//import static java.util.Objects.requireNonNull;
//
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.Executor;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.function.Function;
//
//import com.linecorp.centraldogma.common.PathPattern;
//import com.linecorp.centraldogma.common.Revision;
//import com.linecorp.centraldogma.internal.client.AbstractWatcher;
//
//final class FilesWatcher<T> extends AbstractWatcher<T> {
//
//    private final CentralDogma client;
//    private final PathPattern pathPattern;
//    private final Function<Revision, ? extends T> function;
//    private final Executor functionExecutor;
//
//    FilesWatcher(CentralDogmaRepository centralDogmaRepo, PathPattern pathPattern,
//                 ScheduledExecutorService watchScheduler,
//                 long timeoutMillis, boolean errorOnEntryNotFound,
//                 Function<Revision, ? extends T> function, Executor functionExecutor,
//                 long delayOnSuccessMillis, long initialDelayMillis,
//                 long maxDelayMillis, double multiplier, double jitterRate) {
//        super(watchScheduler, centralDogmaRepo.projectName(), centralDogmaRepo.repositoryName(),
//              pathPattern.get(), timeoutMillis, errorOnEntryNotFound, delayOnSuccessMillis,
//              initialDelayMillis, maxDelayMillis, multiplier, jitterRate);
//        client = centralDogmaRepo.centralDogma();
//        this.pathPattern = requireNonNull(pathPattern, "pathPattern");
//        this.function = requireNonNull(function, "function");
//        this.functionExecutor = requireNonNull(functionExecutor, "functionExecutor");
//    }
//
//    @Override
//    protected CompletableFuture<Latest<T>> doWatch(String projectName, String repositoryName,
//                                                   Revision lastKnownRevision,
//                                                   long timeoutMillis, boolean errorOnEntryNotFound) {
//        return client.watchRepository(projectName, repositoryName, lastKnownRevision, pathPattern,
//                                      timeoutMillis, errorOnEntryNotFound)
//                     .thenApplyAsync(revision -> {
//                         if (revision == null) {
//                             return null;
//                         }
//                         return new Latest<>(revision, function.apply(revision));
//                     }, functionExecutor);
//    }
//}
