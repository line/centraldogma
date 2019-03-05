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
package com.linecorp.centraldogma.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.converters.FileConverter;

import jnr.posix.POSIXFactory;

/**
 * Entry point of a standalone server. Use {@link CentralDogmaBuilder} to embed a server.
 */
public final class Main {

    enum State {
        NONE,
        INITIALIZED,
        STARTED,
        STOPPED,
        DESTROYED
    }

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final File DEFAULT_DATA_DIR =
            new File(System.getProperty("user.dir", ".") + File.separatorChar + "data");

    private static final File DEFAULT_CONFIG_FILE =
            new File(System.getProperty("user.dir", ".") +
                     File.separatorChar + "conf" +
                     File.separatorChar + "dogma.json");

    @Nullable
    @Parameter(names = "-config", description = "The path to the config file", converter = FileConverter.class)
    private File configFile;

    @Nullable
    @Parameter(names = "-pidfile",
            description = "The path to the file containing the pid of the server" +
                          " (defaults to /var/run/centraldogma.pid)",
            converter = FileConverter.class)
    private File pidFile;

    /**
     * Note that {@link Boolean} was used in lieu of {@code boolean} so that JCommander does not print the
     * default value of this option.
     */
    @Nullable
    @Parameter(names = { "-help", "-h" }, description = "Prints the usage", help = true)
    private Boolean help;

    private State state = State.NONE;
    @Nullable
    private CentralDogma dogma;
    @Nullable
    private PidFile procIdFile;

    private Main(String[] args) {
        final JCommander commander = new JCommander(this);
        commander.setProgramName(getClass().getName());
        commander.parse(args);

        if (help != null && help) {
            commander.usage();
        } else {
            procIdFile = new PidFile(Optional.ofNullable(pidFile)
                                             .orElseGet(() -> new File("/var/run/centraldogma.pid")));
            state = State.INITIALIZED;
        }
    }

    synchronized void start() throws Exception {
        switch (state) {
            case NONE:
                throw new IllegalStateException("not initialized");
            case STARTED:
                throw new IllegalStateException("started already");
            case DESTROYED:
                throw new IllegalStateException("can't start after destruction");
            default:
                break;
        }

        final File configFile = findConfigFile(this.configFile, DEFAULT_CONFIG_FILE);

        final CentralDogma dogma;
        if (configFile == null) {
            dogma = new CentralDogmaBuilder(DEFAULT_DATA_DIR).build();
        } else {
            dogma = CentralDogma.forConfig(configFile);
        }

        dogma.start().get();

        this.dogma = dogma;
        state = State.STARTED;

        // The server would be stopped even if we fail to create the PID file from here,
        // because the state has been updated.
        assert procIdFile != null;
        procIdFile.create();
    }

    @Nullable
    private static File findConfigFile(@Nullable File file, File defaultFile) {
        if (file != null) {
            if (file.isFile() && file.canRead()) {
                return file;
            } else {
                throw new IllegalStateException("cannot access the specified config file: " + file);
            }
        }

        // Try to use the default config file if not specified.
        if (defaultFile.isFile() && defaultFile.canRead()) {
            return defaultFile;
        }
        return null;
    }

    synchronized void stop() throws Exception {
        switch (state) {
            case NONE:
            case INITIALIZED:
            case STOPPED:
                return;
            case DESTROYED:
                throw new IllegalStateException("can't stop after destruction");
        }

        final CentralDogma dogma = this.dogma;
        assert dogma != null;
        this.dogma = null;
        dogma.stop().get();

        state = State.STOPPED;
    }

    void destroy() {
        switch (state) {
            case NONE:
                return;
            case STARTED:
                throw new IllegalStateException("can't destroy while running");
            case DESTROYED:
                return;
        }

        assert procIdFile != null;
        try {
            procIdFile.destroy();
        } catch (IOException e) {
            logger.warn("Failed to destroy the PID file:", e);
        }

        state = State.DESTROYED;
    }

    /**
     * Starts a new Central Dogma server.
     */
    public static void main(String[] args) throws Exception {
        final Main main = new Main(args);

        // Register the shutdown hook.
        Runtime.getRuntime().addShutdownHook(new Thread("Central Dogma shutdown hook") {
            @Override
            public void run() {
                try {
                    main.stop();
                } catch (Exception e) {
                    logger.warn("Failed to stop the Central Dogma:", e);
                }

                try {
                    main.destroy();
                } catch (Exception e) {
                    logger.warn("Failed to destroy the Central Dogma:", e);
                }
            }
        });

        // Exit if initialization failed.
        if (main.state != State.INITIALIZED) {
            System.exit(1);
            return;
        }

        try {
            main.start();
        } catch (Throwable cause) {
            logger.error("Failed to start the Central Dogma:", cause);
            // Trigger the shutdown hook.
            System.exit(1);
        }
    }

    /**
     * Manages a process ID file for the Central Dogma server.
     */
    static final class PidFile {

        private final File file;

        private PidFile(File file) {
            this.file = file;
        }

        void create() throws IOException {
            if (file.exists()) {
                throw new IllegalStateException("Failed to create a PID file. A file already exists: " +
                                                file.getPath());
            }

            final int pid = POSIXFactory.getPOSIX().getpid();
            final Path temp = Files.createTempFile("central-dogma", ".tmp");
            Files.write(temp, Integer.toString(pid).getBytes());
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file.toPath());
            }

            logger.debug("A PID file has been created: {}", file);
        }

        void destroy() throws IOException {
            if (Files.deleteIfExists(file.toPath())) {
                logger.debug("Successfully deleted the PID file: {}", file);
            }
        }
    }
}
