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

    public static TemplateParams of(boolean renderTemplate, @Nullable String variableFile,
                                    @Nullable Revision templateRevision) {
        if (!renderTemplate) {
            return disabled();
        } else {
            return new TemplateParams(true, variableFile, templateRevision);
        }
    }

    private final boolean renderTemplate;
    @Nullable
    private final String variableFile;
    @Nullable
    private final Revision templateRevision;

    private TemplateParams(boolean renderTemplate, @Nullable String variableFile,
                           @Nullable Revision templateRevision) {
        this.renderTemplate = renderTemplate;
        this.variableFile = variableFile;
        this.templateRevision = templateRevision;
    }

    public boolean renderTemplate() {
        return renderTemplate;
    }

    @Nullable
    public String variableFile() {
        return variableFile;
    }

    @Nullable
    public Revision templateRevision() {
        return templateRevision;
    }

    public TemplateParams withTemplateRevision(Revision templateRevision) {
        if (Objects.equals(templateRevision, this.templateRevision)) {
            return this;
        }
        return new TemplateParams(renderTemplate, variableFile, templateRevision);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TemplateParams)) {
            return false;
        }
        final TemplateParams that = (TemplateParams) o;
        return renderTemplate == that.renderTemplate &&
               Objects.equals(variableFile, that.variableFile) &&
               Objects.equals(templateRevision, that.templateRevision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(renderTemplate, variableFile, templateRevision);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("renderTemplate", renderTemplate)
                          .add("variableFile", variableFile)
                          .add("templateRevision", templateRevision)
                          .toString();
    }
}
