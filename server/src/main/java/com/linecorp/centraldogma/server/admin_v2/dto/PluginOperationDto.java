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

package com.linecorp.centraldogma.server.admin_v2.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.linecorp.centraldogma.internal.thrift.PluginOperation;

public class PluginOperationDto {
    private String pluginName;
    private String operationName;
    private List<PluginOperationParamDefDto> paramDefs = new ArrayList<>();
    private PluginOperationReturnDefDto returnDef;
    private CommentDto comment;

    public PluginOperationDto() {}

    public PluginOperationDto(PluginOperation pluginOperation) {
        pluginName = pluginOperation.getPluginName();
        operationName = pluginOperation.getOperationName();
        paramDefs = pluginOperation.getParamDefs().stream()
                .map(PluginOperationParamDefDto::new).collect(Collectors.toList());
        returnDef = new PluginOperationReturnDefDto(pluginOperation.getReturnDef());
        if (pluginOperation.getComment() != null) {
            comment = new CommentDto(pluginOperation.getComment());
        }
    }

    public String getPluginName() {
        return pluginName;
    }

    public void setPluginName(String pluginName) {
        this.pluginName = pluginName;
    }

    public String getOperationName() {
        return operationName;
    }

    public void setOperationName(String operationName) {
        this.operationName = operationName;
    }

    public List<PluginOperationParamDefDto> getParamDefs() {
        return paramDefs;
    }

    public void setParamDefs(List<PluginOperationParamDefDto> paramDefs) {
        this.paramDefs = paramDefs;
    }

    public PluginOperationReturnDefDto getReturnDef() {
        return returnDef;
    }

    public void setReturnDef(PluginOperationReturnDefDto returnDef) {
        this.returnDef = returnDef;
    }

    public CommentDto getComment() {
        return comment;
    }

    public void setComment(CommentDto comment) {
        this.comment = comment;
    }
}
