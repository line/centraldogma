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

package com.linecorp.centraldogma.server.internal.admin.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.linecorp.centraldogma.internal.thrift.Project;

public class ProjectDto implements Serializable {

    private static final long serialVersionUID = -537677643104565582L;

    private String name;
    private SchemaDto schema;
    private List<PluginDto> plugins = new ArrayList<>();

    public ProjectDto() {}

    public ProjectDto(Project project) {
        name = project.getName();
        if (project.getSchema() != null) {
            schema = new SchemaDto(project.getSchema());
        }
        if (project.getPlugins() != null) {
            plugins = project.getPlugins().stream().map(PluginDto::new).collect(Collectors.toList());
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SchemaDto getSchema() {
        return schema;
    }

    public void setSchema(SchemaDto schema) {
        this.schema = schema;
    }

    public List<PluginDto> getPlugins() {
        return plugins;
    }

    public void setPlugins(List<PluginDto> plugins) {
        this.plugins = plugins;
    }
}
