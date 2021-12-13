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
//import java.time.Duration;
//import java.util.concurrent.Executor;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.function.Function;
//
//import com.linecorp.centraldogma.common.PathPattern;
//import com.linecorp.centraldogma.common.Revision;
//
//public final class FilesWatcherBuilder<T> extends WatchOptions {
//
//    private final CentralDogmaRepository centralDogmaRepo;
//    private final PathPattern pathPattern;
//    private final ScheduledExecutorService blockingTaskExecutor;
//    private final Function<Revision, ? extends T> function;
//    private final Executor executor;
//
//    FilesWatcherBuilder(CentralDogmaRepository centralDogmaRepo, PathPattern pathPattern,
//                        ScheduledExecutorService blockingTaskExecutor,
//                        long timeoutMillis, boolean errorOnEntryNotFound,
//                        Function<Revision, ? extends T> function, Executor executor) {
//        super(timeoutMillis, errorOnEntryNotFound);
//        this.centralDogmaRepo = centralDogmaRepo;
//        this.pathPattern = pathPattern;
//        this.blockingTaskExecutor = blockingTaskExecutor;
//        this.function = function;
//        this.executor = executor;
//    }
//
//    /**
//     * Sets the {@link Function} that convert the result of a watch.
//     */
//    public <U> FilesWatcherBuilder<U> map(Function<? super T, ? extends U> function) {
//        return map(function, blockingTaskExecutor);
//    }
//
//    /**
//     * Sets the {@link Function} that convert the result of a watch. The {@link Function} is executed by
//     * the specified {@link Executor}.
//     */
//    public <U> FilesWatcherBuilder<U> map(Function<? super T, ? extends U> function, Executor executor) {
//        requireNonNull(function, "function");
//        requireNonNull(executor, "executor");
//        return new FilesWatcherBuilder<>(centralDogmaRepo, pathPattern, blockingTaskExecutor,
//                                         timeoutMillis(), errorOnEntryNotFound(),
//                                         this.function.andThen(function), executor);
//    }
//
//    @Override
//    public FilesWatcherBuilder<T> timeout(Duration timeout) {
//        //noinspection unchecked
//        return (FilesWatcherBuilder<T>) super.timeout(timeout);
//    }
//
//    @Override
//    public FilesWatcherBuilder<T> timeoutMillis(long timeoutMillis) {
//        //noinspection unchecked
//        return (FilesWatcherBuilder<T>) super.timeoutMillis(timeoutMillis);
//    }
//
//    @Override
//    public FilesWatcherBuilder<T> errorOnEntryNotFound(boolean errorOnEntryNotFound) {
//        //noinspection unchecked
//        return (FilesWatcherBuilder<T>) super.errorOnEntryNotFound(errorOnEntryNotFound);
//    }
//
//    @Override
//    public FilesWatcherBuilder<T> delayOnSuccess(Duration delayOnSuccess) {
//        //noinspection unchecked
//        return (FilesWatcherBuilder<T>) super.delayOnSuccess(delayOnSuccess);
//    }
//
//    @Override
//    public FilesWatcherBuilder<T> delayOnSuccessMillis(long delayOnSuccessMillis) {
//        //noinspection unchecked
//        return (FilesWatcherBuilder<T>) super.delayOnSuccessMillis(delayOnSuccessMillis);
//    }
//
//    @Override
//    public FilesWatcherBuilder<T> backoffOnFailure(long initialDelayMillis, long maxDelayMillis,
//                                                   double multiplier) {
//        //noinspection unchecked
//        return (FilesWatcherBuilder<T>) super.backoffOnFailure(initialDelayMillis, maxDelayMillis, multiplier);
//    }
//
//    @Override
//    public FilesWatcherBuilder<T> jitterRate(double jitterRate) {
//        //noinspection unchecked
//        return (FilesWatcherBuilder<T>) super.jitterRate(jitterRate);
//    }
//
//    public FilesWatcher<T> build() {
//        return new FilesWatcher<>(centralDogmaRepo, pathPattern, blockingTaskExecutor, timeoutMillis(),
//                                  errorOnEntryNotFound(), function, executor, delayOnSuccessMillis(),
//                                  initialDelayMillis(), maxDelayMillis(), multiplier(), jitterRate());
//    }
//}
