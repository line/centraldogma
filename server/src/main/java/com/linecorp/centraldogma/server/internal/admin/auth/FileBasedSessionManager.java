/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.admin.auth;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static org.quartz.CronScheduleBuilder.cronSchedule;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;
import static org.quartz.core.jmx.JobDataMapSupport.newJobDataMap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.auth.AuthConfig;
import com.linecorp.centraldogma.server.auth.AuthException;
import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.auth.SessionManager;

/**
 * A {@link SessionManager} based on the file system. The sessions stored in the file system would be
 * deleted when the {@link #delete(String)} method is called or when the {@link ExpiredSessionDeletingJob}
 * finds the expired session. The {@link AuthConfig#sessionValidationSchedule()} can configure
 * the schedule for deleting expired sessions.
 */
public final class FileBasedSessionManager implements SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(FileBasedSessionManager.class);

    private static final int SESSION_ID_LENGTH = nextSessionId().length();
    private static final int SESSION_ID_1ST_PART_LENGTH = 2;
    private static final Pattern SESSION_ID_1ST_PART_PATTERN = Pattern.compile(
            "^[0-9a-f]{" + SESSION_ID_1ST_PART_LENGTH + "}$");
    private static final int SESSION_ID_2ND_PART_LENGTH = SESSION_ID_LENGTH - SESSION_ID_1ST_PART_LENGTH;
    private static final Pattern SESSION_ID_2ND_PART_PATTERN =
            Pattern.compile("^[-0-9a-f]{" + SESSION_ID_2ND_PART_LENGTH + "}$");
    private static final Pattern SESSION_ID_PATTERN =
            Pattern.compile("^[-0-9a-f]{" + SESSION_ID_LENGTH + "}$");

    private static final String ROOT_DIR = "ROOT_DIR";

    private final Path rootDir;
    private final Path tmpDir;

    @Nullable
    private final Scheduler scheduler;

    /**
     * Creates a new instance.
     *
     * @param rootDir the {@link Path} that the sessions are kept
     * @param cronExpr the cron expression which specifies the schedule for deleting expired sessions.
     *                 {@code null} to disable the session expiration.
     */
    public FileBasedSessionManager(
            Path rootDir, @Nullable String cronExpr) throws IOException, SchedulerException {
        this.rootDir = requireNonNull(rootDir, "rootDir");

        tmpDir = rootDir.resolve("tmp");
        Files.createDirectories(tmpDir);

        if (cronExpr != null) {
            scheduler = createScheduler(cronExpr);
            scheduler.start();
        } else {
            scheduler = null;
        }
    }

    private Scheduler createScheduler(String cronExpr) throws SchedulerException {
        // The scheduler can be started and stopped several times in JUnit tests, but Quartz holds
        // every scheduler instances in a singleton SchedulerRepository. So it's possible to pick up
        // the scheduler which is going to be stopped if we use the same instance name for every scheduler,
        // because CentralDogmaExtension stops the server asynchronously using another thread.
        final String myInstanceId = String.valueOf(hashCode());

        final Properties cfg = new Properties();
        cfg.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        cfg.setProperty("org.quartz.scheduler.instanceName",
                        FileBasedSessionManager.class.getSimpleName() + '@' + myInstanceId);
        cfg.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        cfg.setProperty("org.quartz.threadPool.threadCount", "1");

        final Scheduler scheduler = new StdSchedulerFactory(cfg).getScheduler();

        final JobDetail job = newJob(ExpiredSessionDeletingJob.class)
                .usingJobData(newJobDataMap(ImmutableMap.of(ROOT_DIR, rootDir)))
                .build();

        final Trigger trigger = newTrigger()
                .withIdentity(myInstanceId, ExpiredSessionDeletingJob.class.getSimpleName())
                .withSchedule(cronSchedule(cronExpr))
                .build();

        scheduler.scheduleJob(job, trigger);
        return scheduler;
    }

    @Override
    public String generateSessionId() {
        return nextSessionId();
    }

    private static String nextSessionId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public CompletableFuture<Boolean> exists(String sessionId) {
        requireNonNull(sessionId, "sessionId");
        return CompletableFuture.completedFuture(isSessionFile(sessionId2PathOrNull(sessionId)));
    }

    @Override
    public CompletableFuture<Session> get(String sessionId) {
        requireNonNull(sessionId, "sessionId");

        final Path path = sessionId2PathOrNull(sessionId);
        if (!isSessionFile(path)) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return CompletableFuture.completedFuture(
                    Jackson.readValue(Files.readAllBytes(path), Session.class));
        } catch (IOException e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public CompletableFuture<Void> create(Session session) {
        requireNonNull(session, "session");
        return CompletableFuture.supplyAsync(() -> {
            final String sessionId = session.id();
            final Path newPath = sessionId2Path(sessionId);
            try {
                try {
                    // Create the parent directory if not exists.
                    Files.createDirectories(newPath.getParent());
                } catch (FileAlreadyExistsException e) {
                    // It exists but it is not a directory.
                    throw new AuthException(e);
                }

                final Path tmpPath = Files.createTempFile(tmpDir, null, null);
                Files.write(tmpPath, Jackson.writeValueAsBytes(session));
                Files.move(tmpPath, newPath, StandardCopyOption.ATOMIC_MOVE);
                return null;
            } catch (FileAlreadyExistsException unused) {
                throw new AuthException("duplicate session: " + sessionId);
            } catch (IOException e) {
                throw new AuthException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> update(Session session) {
        requireNonNull(session, "session");
        return CompletableFuture.supplyAsync(() -> {
            final String sessionId = session.id();
            final Path oldPath = sessionId2Path(sessionId);
            if (!Files.exists(oldPath)) {
                throw new AuthException("unknown session: " + sessionId);
            }

            try {
                final Path newPath = Files.createTempFile(tmpDir, null, null);
                Files.write(newPath, Jackson.writeValueAsBytes(session));
                Files.move(newPath, oldPath,
                           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return null;
            } catch (IOException e) {
                throw new AuthException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> delete(String sessionId) {
        requireNonNull(sessionId, "sessionId");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.deleteIfExists(sessionId2Path(sessionId));
            } catch (IOException e) {
                throw new AuthException(e);
            }
            return null;
        });
    }

    @Override
    public void close() throws Exception {
        if (scheduler != null && !scheduler.isShutdown()) {
            // Graceful shutdown.
            // We don't use InterruptableJob for simplicity, but just waiting for the job to be completed.
            scheduler.shutdown(true);
        }
    }

    private Path sessionId2Path(String sessionId) {
        final Path path = sessionId2PathOrNull(sessionId);
        checkArgument(path != null, "sessionId: %s (expected: UUID)", sessionId);
        return path;
    }

    @Nullable
    private Path sessionId2PathOrNull(String sessionId) {
        return sessionId2PathOrNull(rootDir, sessionId);
    }

    @Nullable
    private static Path sessionId2PathOrNull(Path rootDir, String sessionId) {
        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return null;
        }
        return rootDir.resolve(sessionId.substring(0, SESSION_ID_1ST_PART_LENGTH))
                      .resolve(sessionId.substring(SESSION_ID_1ST_PART_LENGTH));
    }

    private static boolean isSessionFile(@Nullable Path path) {
        if (path == null) {
            return false;
        }
        if (!Files.isRegularFile(path)) {
            return false;
        }
        final int nameCount = path.getNameCount();
        if (nameCount < 2) {
            return false;
        }

        final String first = path.getName(nameCount - 2).toString();
        if (!SESSION_ID_1ST_PART_PATTERN.matcher(first).matches()) {
            return false;
        }

        final String second = path.getName(nameCount - 1).toString();
        return SESSION_ID_2ND_PART_PATTERN.matcher(second).matches();
    }

    /**
     * A job for deleting expired sessions from the file system.
     */
    public static class ExpiredSessionDeletingJob implements Job {
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                logger.debug("Started {} job.", ExpiredSessionDeletingJob.class.getSimpleName());
                final Path rootDir = (Path) context.getJobDetail().getJobDataMap().get(ROOT_DIR);
                final Instant now = Instant.now();
                try (Stream<Path> stream = Files.walk(rootDir, 2)) {
                     stream.filter(FileBasedSessionManager::isSessionFile)
                     .map(path -> {
                         try {
                             return Jackson.readValue(Files.readAllBytes(path), Session.class);
                         } catch (FileNotFoundException | NoSuchFileException ignored) {
                             // Session deleted by other party.
                         } catch (Exception e) {
                             logger.warn("Failed to deserialize a session: {}", path, e);
                             try {
                                 Files.deleteIfExists(path);
                                 logger.debug("Deleted an invalid session: {}", path);
                             } catch (IOException cause) {
                                 logger.warn("Failed to delete an invalid session: {}", path, cause);
                             }
                         }
                         return null;
                     })
                     .filter(Objects::nonNull)
                     .filter(session -> now.isAfter(session.expirationTime()))
                     .forEach(session -> {
                         final Path path = sessionId2PathOrNull(rootDir, session.id());
                         if (path == null) {
                             return;
                         }
                         try {
                             Files.deleteIfExists(path);
                             logger.debug("Deleted an expired session: {}", path);
                         } catch (Throwable cause) {
                             logger.warn("Failed to delete an expired session: {}", path, cause);
                         }
                     });
                }
                logger.debug("Finished {} job.", ExpiredSessionDeletingJob.class.getSimpleName());
            } catch (Throwable cause) {
                logger.warn("Failed {} job:", ExpiredSessionDeletingJob.class.getSimpleName(), cause);
            }
        }
    }
}
