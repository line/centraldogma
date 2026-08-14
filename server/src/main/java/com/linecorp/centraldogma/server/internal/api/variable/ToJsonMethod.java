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
package com.linecorp.centraldogma.server.internal.api.variable;

import java.util.List;

import com.linecorp.centraldogma.internal.Jackson;

import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateModelException;
import freemarker.template.utility.DeepUnwrap;

/**
 * A FreeMarker method that serializes a template variable (map, list, scalar) to a compact JSON string.
 * Usage in templates: {@code ${toJson(vars.someObject)}}
 */
final class ToJsonMethod implements TemplateMethodModelEx {

    @Override
    public Object exec(List arguments) throws TemplateModelException {
        if (arguments.size() != 1) {
            throw new TemplateModelException("toJson requires exactly 1 argument");
        }
        final Object unwrapped = DeepUnwrap.unwrap((TemplateModel) arguments.get(0));
        try {
            return Jackson.writeValueAsString(unwrapped);
        } catch (Exception e) {
            throw new TemplateModelException("Failed to serialize to JSON", e);
        }
    }
}
