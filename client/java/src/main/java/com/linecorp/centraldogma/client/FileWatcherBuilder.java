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
//import static com.linecorp.centraldogma.internal.Util.unsafeCast;
//import static java.util.Objects.requireNonNull;
//
//import java.time.Duration;
//import java.util.concurrent.Executor;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.function.Function;
//
//import com.linecorp.centraldogma.common.Query;
//
//public final class FileWatcherBuilder<T> extends WatchOptions {
//
//    private final CentralDogmaRepository centralDogmaRepo;
//    private final Query<?> query;
//    private final ScheduledExecutorService blockingTaskExecutor;
//    private final Function<Object, ? extends T> function;
//    private final Executor executor;
//
//    <U> FileWatcherBuilder(CentralDogmaRepository centralDogmaRepo, Query<U> query,
//                           ScheduledExecutorService blockingTaskExecutor,
//                           long timeoutMillis, boolean errorOnEntryNotFound,
//                           Function<? super U, ? extends T> function, Executor executor) {
//        super(timeoutMillis, errorOnEntryNotFound);
//        this.centralDogmaRepo = centralDogmaRepo;
//        this.query = query;
//        this.blockingTaskExecutor = blockingTaskExecutor;
//        this.function = unsafeCast(function);
//        this.executor = executor;
//    }
//
//    /**
//     * Sets the {@link Function} that convert the result of a watch.
//     */
//    public <U> FileWatcherBuilder<U> map(Function<? super T, ? extends U> function) {
//        return map(function, executor);
//    }
//
//    /**
//     * Sets the {@link Function} that convert the result of a watch. The {@link Function} is executed by
//     * the specified {@link Executor}.
//     */
//    public <U> FileWatcherBuilder<U> map(Function<? super T, ? extends U> function, Executor executor) {
//        requireNonNull(function, "function");
//        requireNonNull(executor, "executor");
//        return new FileWatcherBuilder<>(centralDogmaRepo, query, blockingTaskExecutor, timeoutMillis(),
//                                        errorOnEntryNotFound(), this.function.andThen(function), executor);
//    }
//
//    @Override
//    public FileWatcherBuilder<T> timeout(Duration timeout) {
//        //noinspection unchecked
//        return (FileWatcherBuilder<T>) super.timeout(timeout);
//    }
//
//    @Override
//    public FileWatcherBuilder<T> timeoutMillis(long timeoutMillis) {
//        //noinspection unchecked
//        return (FileWatcherBuilder<T>) super.timeoutMillis(timeoutMillis);
//    }
//
//    @Override
//    public FileWatcherBuilder<T> errorOnEntryNotFound(boolean errorOnEntryNotFound) {
//        //noinspection unchecked
//        return (FileWatcherBuilder<T>) super.errorOnEntryNotFound(errorOnEntryNotFound);
//    }
//
//    @Override
//    public FileWatcherBuilder<T> delayOnSuccess(Duration delayOnSuccess) {
//        //noinspection unchecked
//        return (FileWatcherBuilder<T>) super.delayOnSuccess(delayOnSuccess);
//    }
//
//    @Override
//    public FileWatcherBuilder<T> delayOnSuccessMillis(long delayOnSuccessMillis) {
//        //noinspection unchecked
//        return (FileWatcherBuilder<T>) super.delayOnSuccessMillis(delayOnSuccessMillis);
//    }
//
//    @Override
//    public FileWatcherBuilder<T> backoffOnFailure(long initialDelayMillis, long maxDelayMillis,
//                                                  double multiplier) {
//        //noinspection unchecked
//        return (FileWatcherBuilder<T>) super.backoffOnFailure(initialDelayMillis, maxDelayMillis, multiplier);
//    }
//
//    @Override
//    public FileWatcherBuilder<T> jitterRate(double jitterRate) {
//        //noinspection unchecked
//        return (FileWatcherBuilder<T>) super.jitterRate(jitterRate);
//    }
//
//    public FileWatcher<T> build() {
//        return new FileWatcher<T>(centralDogmaRepo, query, blockingTaskExecutor, timeoutMillis(),
//                                  errorOnEntryNotFound(), function, executor, delayOnSuccessMillis(),
//                                  initialDelayMillis(), maxDelayMillis(), multiplier(), jitterRate());
//    }
//}
