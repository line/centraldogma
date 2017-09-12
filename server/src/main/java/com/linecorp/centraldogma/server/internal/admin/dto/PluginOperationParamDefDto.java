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

import com.linecorp.centraldogma.internal.thrift.PluginOperationParamDef;

public class PluginOperationParamDefDto {
    private String name;
    private String type;
    private CommentDto comment;

    public PluginOperationParamDefDto() {
    }

    public PluginOperationParamDefDto(PluginOperationParamDef paramDef) {
        name = paramDef.getName();
        type = paramDef.getType().name();
        if (paramDef.getComment() != null) {
            comment = new CommentDto(paramDef.getComment());
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public CommentDto getComment() {
        return comment;
    }

    public void setComment(CommentDto comment) {
        this.comment = comment;
    }
}
