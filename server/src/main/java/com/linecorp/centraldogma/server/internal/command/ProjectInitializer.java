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

package com.linecorp.centraldogma.server.internal.command;

import static com.linecorp.centraldogma.server.internal.command.Command.createProject;
import static com.linecorp.centraldogma.server.internal.command.Command.createRepository;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectExistsException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryExistsException;

// TODO(trustin): Generate more useful set of sample files.
public final class ProjectInitializer {

    private static final Logger logger = LoggerFactory.getLogger(ProjectInitializer.class);

    private static final String[] SAMPLE_TEXT = {
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Curabitur sit" +
            " amet mauris eu tortor fringilla tempus. Suspendisse commodo quam " +
            "justo, finibus ultricies dui placerat in. Vestibulum vel ornare eros. " +
            "Fusce in accumsan orci, vitae tempor mauris. Mauris eget ex sed nunc " +
            "lacinia tincidunt sit amet eu nisl. Aenean condimentum nec enim in " +
            "rutrum. Curabitur sed leo justo. Pellentesque urna mauris, tristique " +
            "non ex id, sagittis imperdiet metus. Proin ac iaculis sapien.",

            "Donec sollicitudin arcu nulla, quis tincidunt nibh mollis vel. Integer" +
            " in velit iaculis, posuere sapien sed, hendrerit lorem. In non nisl " +
            "pharetra, maximus tellus sit amet, tincidunt lectus. Etiam eleifend " +
            "turpis sit amet ex elementum varius. Praesent ac risus vel nisl porta " +
            "laoreet nec ac urna. Morbi non vehicula sapien, nec placerat nisi. " +
            "Curabitur molestie laoreet vehicula. Phasellus non quam vitae arcu " +
            "tristique tempus. Sed tincidunt, libero ut ullamcorper eleifend, purus" +
            " dolor laoreet dolor, ut semper ante eros id diam. Ut lacinia odio eu " +
            "urna fringilla luctus cursus non justo. Vestibulum quam tellus, " +
            "sodales sit amet volutpat congue, semper vitae justo. Curabitur " +
            "porttitor eros eros, vitae rhoncus tortor bibendum placerat.",

            "Pellentesque feugiat, est sit amet condimentum sagittis, ligula odio " +
            "mattis dui, placerat volutpat purus quam eu nunc. Proin ex nibh, " +
            "euismod nec malesuada at, mattis in lacus. Nulla tempus accumsan " +
            "imperdiet. Quisque quis gravida tellus. Quisque sollicitudin lorem sed" +
            " egestas eleifend. Nunc tempus ac libero nec fermentum. Donec at orci " +
            "malesuada elit egestas ultrices. In mattis ac nunc et auctor. " +
            "Pellentesque vel dui eget metus finibus pellentesque sed vel risus. " +
            "Suspendisse sed pellentesque nulla. Cum sociis natoque penatibus et " +
            "magnis dis parturient montes, nascetur ridiculus mus. Ut fermentum " +
            "interdum sem, luctus egestas velit lacinia pretium. In id convallis " +
            "velit.",

            "Aliquam id dictum dolor, vitae vestibulum eros. Aliquam malesuada " +
            "dignissim placerat. Etiam sagittis eu lectus non pharetra. Sed sed " +
            "fringilla diam, in faucibus elit. Quisque non fringilla augue. Donec a" +
            " urna eu sapien ultricies laoreet a eu risus. Nunc imperdiet, enim non" +
            " hendrerit lobortis, nisi eros varius velit, ut ultrices mi elit eget " +
            "est. Pellentesque at suscipit nulla. Vivamus facilisis, turpis " +
            "ultrices accumsan placerat, odio lectus bibendum elit, quis mollis " +
            "massa neque at turpis. Proin felis felis, egestas eget velit a, " +
            "tristique semper mauris. Etiam aliquam enim vitae tellus blandit, id " +
            "cursus lectus ultrices. Aliquam vel risus elit. Donec pharetra ligula " +
            "eu ipsum eleifend, eu rutrum nibh mattis. Phasellus in semper dui, id " +
            "fermentum eros. Nulla laoreet sapien quis sollicitudin bibendum.",

            "Morbi euismod ultrices erat, id efficitur turpis euismod sed. Vivamus " +
            "hendrerit rutrum venenatis. Phasellus volutpat bibendum metus, in " +
            "rutrum elit. Phasellus vitae venenatis velit. Fusce quis maximus lacus" +
            ". Morbi nec maximus eros. Vestibulum est ipsum, dictum tincidunt " +
            "viverra ut, varius id sapien. Donec dui augue, bibendum nec orci sed, " +
            "ultricies commodo erat. Nunc tincidunt suscipit neque, vitae tincidunt" +
            " turpis pharetra a. Nulla facilisis efficitur orci, a porttitor odio. " +
            "Donec vulputate erat eget ultricies dignissim. Duis placerat, sapien " +
            "et tincidunt posuere, turpis diam tempor neque, vel aliquet enim orci " +
            "vel nunc. Donec convallis ex tellus, vel iaculis mi vestibulum at. " +
            "Phasellus eu metus est."
    };

    private static final String SAMPLE_JSON_OBJECT =
            "{ \"a\": \"" + SAMPLE_TEXT[0] + "\",\n" +
            "  \"b\": \"" + SAMPLE_TEXT[1] + "\",\n" +
            "  \"c\": \"" + SAMPLE_TEXT[2] + "\",\n" +
            "  \"d\": \"" + SAMPLE_TEXT[3] + "\",\n" +
            "  \"e\": \"" + SAMPLE_TEXT[4] + "\" }";

    private static final String SAMPLE_JSON_ARRAY =
            "[ \"" + SAMPLE_TEXT[0] + "\",\n" +
            "  \"" + SAMPLE_TEXT[1] + "\",\n" +
            "  \"" + SAMPLE_TEXT[2] + "\",\n" +
            "  \"" + SAMPLE_TEXT[3] + "\",\n" +
            "  \"" + SAMPLE_TEXT[4] + "\" ]";

    public static final String INTERNAL_PROJECT_NAME = "dogma";

    static CompletableFuture<Revision> generateSampleFiles(
            CommandExecutor executor, String projectName, String repositoryName) {

        // Do not generate sample files for internal projects.
        if (projectName.equals(INTERNAL_PROJECT_NAME)) {
            return CompletableFuture.completedFuture(Revision.INIT);
        }

        logger.info("Generating sample files into: {}/{}", projectName, repositoryName);

        final List<Change<?>> changes = Arrays.asList(
                Change.ofTextUpsert("/samples/foo.txt", String.join("\n\n", SAMPLE_TEXT)),
                Change.ofJsonUpsert("/samples/bar.json", SAMPLE_JSON_OBJECT),
                Change.ofJsonUpsert("/samples/qux.json", SAMPLE_JSON_ARRAY));

        return executor.execute(Command.push(
                Author.SYSTEM, projectName, repositoryName, Revision.HEAD,
                "Add the sample files", "", Markup.PLAINTEXT, changes));
    }

    /**
     * Creates an internal project and repositories such as a token storage.
     */
    public static void initializeInternalProject(CommandExecutor executor) {
        try {
            executor.execute(createProject(Author.SYSTEM, INTERNAL_PROJECT_NAME))
                    .get();
        } catch (Throwable cause) {
            cause = Exceptions.peel(cause);
            if (!(cause instanceof ProjectExistsException)) {
                throw new Error("failed to initialize an internal project", cause);
            }
        }
        for (final String repo : ImmutableList.of(Project.REPO_META,
                                                  Project.REPO_MAIN)) {
            try {
                executor.execute(createRepository(Author.SYSTEM, INTERNAL_PROJECT_NAME, repo))
                        .get();
            } catch (Throwable cause) {
                cause = Exceptions.peel(cause);
                if (!(cause instanceof RepositoryExistsException)) {
                    throw new Error(cause);
                }
            }
        }
    }

    private ProjectInitializer() {}
}
