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
/*
 * Copyright (c) 2014, Francis Galiegue (fgaliegue@gmail.com)
 *
 * This software is dual-licensed under:
 *
 * - the Lesser General Public License (LGPL) version 3.0 or, at your option, any
 *   later version;
 * - the Apache Software License (ASL) version 2.0.
 *
 * The text of this file and of both licenses is available at the root of this
 * project or, if you have the jar distribution, in directory META-INF/, under
 * the names LGPL-3.0.txt and ASL-2.0.txt respectively.
 *
 * Direct link to the sources:
 *
 * - LGPL 3.0: https://www.gnu.org/licenses/lgpl-3.0.txt
 * - ASL 2.0: https://www.apache.org/licenses/LICENSE-2.0.txt
 */

/**
 * Implementation of JSON Patch.
 *
 * <p>As its name implies, JSON Patch is a mechanism designed to modify JSON
 * documents. It consists of a series of operations to apply in order to the
 * source JSON document until all operations are applied or an error has been
 * encountered.</p>
 *
 * <p>The main class is {@link com.linecorp.centraldogma.internal.jsonpatch.JsonPatch}.</p>
 *
 * <p>Note that at this moment, the only way to build a patch is from a JSON
 * representation (as a {@link com.fasterxml.jackson.databind.JsonNode}).</p>
 *
 */
package com.linecorp.centraldogma.internal.jsonpatch;
