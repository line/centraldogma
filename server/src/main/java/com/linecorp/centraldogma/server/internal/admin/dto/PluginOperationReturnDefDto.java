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

import com.linecorp.centraldogma.internal.thrift.PluginOperationReturnDef;

public class PluginOperationReturnDefDto {
    private String type;
    private CommentDto commentDto;

    public PluginOperationReturnDefDto() {}

    public PluginOperationReturnDefDto(PluginOperationReturnDef returnDef) {
        type = returnDef.getType().name();
        if (returnDef.getComment() != null) {
            commentDto = new CommentDto(returnDef.getComment());
        }
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public CommentDto getCommentDto() {
        return commentDto;
    }

    public void setCommentDto(CommentDto commentDto) {
        this.commentDto = commentDto;
    }
}
