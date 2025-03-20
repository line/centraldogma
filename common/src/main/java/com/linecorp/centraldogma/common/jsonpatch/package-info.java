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

/**
 * Implementation of <a href="https://datatracker.ietf.org/doc/html/rfc6902">JSON Patch</a>.
 *
 * <p>As its name implies, JSON Patch is a mechanism designed to modify JSON
 * documents. It consists of a series of operations to apply in order to the
 * source JSON document until all operations are applied or an error has been
 * encountered.</p>
 */
@NonNullByDefault
package com.linecorp.centraldogma.common.jsonpatch;

import com.linecorp.centraldogma.common.util.NonNullByDefault;
