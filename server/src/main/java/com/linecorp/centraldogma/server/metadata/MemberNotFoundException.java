/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.server.metadata;

import com.linecorp.centraldogma.common.CentralDogmaException;

/**
 * A {@link CentralDogmaException} that is raised when failed to find a {@link Member}.
 */
public final class MemberNotFoundException extends CentralDogmaException {

    private static final long serialVersionUID = 914551040812058495L;

    MemberNotFoundException(String memberId, String projectName) {
        super("failed to find member " + memberId + " in '" + projectName + '\'');
    }

    MemberNotFoundException(String memberId, String projectName, String repoName) {
        super("failed to find member " + memberId + " in '" + projectName + '/' + repoName + '\'');
    }
}
