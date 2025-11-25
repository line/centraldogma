/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.api.sysadmin;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.decorator.RequestTimeout;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.EncryptionConfig;
import com.linecorp.centraldogma.server.auth.SessionMasterKey;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.AbstractService;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresSystemAdministrator;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.encryption.SecretKeyWithVersion;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;

@ProducesJson
@RequiresSystemAdministrator
public final class KeyManagementService extends AbstractService {

    private final EncryptionStorageManager encryptionStorageManager;

    public KeyManagementService(CommandExecutor executor, EncryptionStorageManager encryptionStorageManager) {
        super(executor);
        requireNonNull(encryptionStorageManager, "encryptionStorageManager");
        checkArgument(encryptionStorageManager.enabled(), "EncryptionStorageManager must be enabled.");
        this.encryptionStorageManager = encryptionStorageManager;
    }

    @Get("/wdeks")
    @Blocking
    public List<WrappedDekDetails> listWdeks() {
        return encryptionStorageManager.wdeks();
    }

    /**
     * Rotates the wrapped data encryption key (WDEK) for the specified project and repository.
     */
    @Post("/projects/{projectName}/repos/{repoName}/wdeks/rotate")
    @Blocking
    public CompletableFuture<Void> rotateWdek(@Param String projectName, @Param String repoName,
                                              @Param @Default("false") boolean reencrypt,
                                              Author author, ServiceRequestContext ctx) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        if (reencrypt) {
            ctx.setRequestTimeout(Duration.ofSeconds(60));
        }

        final SecretKeyWithVersion currentDek =
                encryptionStorageManager.getCurrentDek(projectName, repoName);
        return encryptionStorageManager.generateWdek().thenCompose(wdek -> {
            // If concurrent rotation happened, only one of them will succeed to create a new WDEK because
            // the storage manager accepts only the WDEK with the version which is exactly one greater.
            final int newVersion = currentDek.version() + 1;
            final WrappedDekDetails wdekDetails = new WrappedDekDetails(
                    wdek, newVersion, encryptionStorageManager.kekId(),
                    projectName, repoName);
            return execute(Command.rotateWdek(author, projectName, repoName, wdekDetails, reencrypt));
        });
    }

    /**
     * Returns the details of the session master key.
     * The actual key material is not included for security reasons.
     */
    @Get("/masterkeys/session")
    @Blocking
    public SessionMasterKeyDto getSessionMasterKeyDetails() {
        if (!encryptionStorageManager.encryptSessionCookie()) {
            throw new IllegalStateException("Session cookie encryption is disabled.");
        }
        final SessionMasterKey sessionMasterKey = encryptionStorageManager.getCurrentSessionMasterKey();
        return new SessionMasterKeyDto(sessionMasterKey.version(),
                                       sessionMasterKey.kekId(),
                                       sessionMasterKey.creationInstant());
    }

    /**
     * Rotates the session master key.
     */
    @Post("/masterkeys/session/rotate")
    @Blocking
    public CompletableFuture<Void> rotateSessionMasterKey(Author author) {
        if (!encryptionStorageManager.encryptSessionCookie()) {
            throw new IllegalStateException("Session cookie encryption is disabled.");
        }
        final SessionMasterKey currentSessionMasterKey = encryptionStorageManager.getCurrentSessionMasterKey();
        // If concurrent rotation happened, only one of them will succeed to create a new session master key
        // because the storage manager accepts only the key with the version which is exactly one greater.
        final SessionMasterKey newSessionMasterKey =
                encryptionStorageManager.generateSessionMasterKey(currentSessionMasterKey.version() + 1).join();
        return execute(Command.rotateSessionMasterKey(author, newSessionMasterKey));
    }

    /**
     * Rewraps all wrapped data encryption keys (WDEKs) and session master keys
     * with the {@link EncryptionConfig#kekId()} specified in the configuration.
     * If the Key Management System does not support automatic key rotation, you should use this API
     * after updating the KEK ID in the configuration and restarting the server.
     * For automatic key rotation,
     * please refer to <a href="https://docs.aws.amazon.com/kms/latest/developerguide/rotate-keys.html">
     * Rotate AWS KMS keys</a>.
     */
    @Post("/keys/rewrap")
    @Blocking
    @RequestTimeout(60000)
    public CompletableFuture<Void> rewrapAllKeys(Author author) {
        return execute(Command.rewrapAllKeys(author));
    }
}
