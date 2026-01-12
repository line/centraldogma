/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.internal.api;

import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Revision;

public final class TemplateParams {

    private static final TemplateParams DISABLED = new TemplateParams(false, null, null);

    public static TemplateParams disabled() {
        return DISABLED;
    }

    public static TemplateParams of(boolean applyTemplate, @Nullable String variableFile,
                                    @Nullable Revision variableRevision) {
        if (!applyTemplate) {
            return disabled();
        } else {
            return new TemplateParams(true, variableFile, variableRevision);
        }
    }

    private final boolean applyTemplate;
    @Nullable
    private final String variableFile;
    @Nullable
    private final Revision variableRevision;

    private TemplateParams(boolean applyTemplate, @Nullable String variableFile,
                           @Nullable Revision variableRevision) {
        this.applyTemplate = applyTemplate;
        this.variableFile = variableFile;
        this.variableRevision = variableRevision;
    }

    public boolean applyTemplate() {
        return applyTemplate;
    }

    @Nullable
    public String variableFile() {
        return variableFile;
    }

    @Nullable
    public Revision variableRevision() {
        return variableRevision;
    }

    public TemplateParams withVariableRevision(Revision variableRevision) {
        if (Objects.equals(variableRevision, this.variableRevision)) {
            return this;
        }
        return new TemplateParams(applyTemplate, variableFile, variableRevision);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TemplateParams)) {
            return false;
        }
        final TemplateParams that = (TemplateParams) o;
        return applyTemplate == that.applyTemplate &&
               Objects.equals(variableFile, that.variableFile) &&
               Objects.equals(variableRevision, that.variableRevision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(applyTemplate, variableFile, variableRevision);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("applyTemplate", applyTemplate)
                          .add("variableFile", variableFile)
                          .add("variableRevision", variableRevision)
                          .toString();
    }
}
