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

package com.linecorp.centraldogma.internal.jsonpatch;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.common.base.Equivalence;
import com.google.common.collect.Sets;

/**
 * An {@link Equivalence} strategy for JSON Schema equality.
 *
 * <p>{@link JsonNode} does a pretty good job of obeying the  {@link
 * Object#equals(Object) equals()}/{@link Object#hashCode() hashCode()}
 * contract. And in fact, it does it too well for JSON Schema.</p>
 *
 * <p>For instance, it considers numeric nodes {@code 1} and {@code 1.0} to be
 * different nodes, which is true. But some IETF RFCs and drafts (among  them,
 * JSON Schema and JSON Patch) mandate that numeric JSON values be considered
 * equal if their mathematical value is the same. This class implements this
 * kind of equality.</p>
 */
final class JsonNumEquals extends Equivalence<JsonNode> {

    private static final Equivalence<JsonNode> INSTANCE = new JsonNumEquals();

    public static Equivalence<JsonNode> getInstance() {
        return INSTANCE;
    }

    private JsonNumEquals() {}

    @Override
    protected boolean doEquivalent(final JsonNode a, final JsonNode b) {
        /*
         * If both are numbers, delegate to the helper method
         */
        if (a.isNumber() && b.isNumber()) {
            return numEquals(a, b);
        }

        final JsonNodeType typeA = a.getNodeType();
        final JsonNodeType typeB = b.getNodeType();

        /*
         * If they are of different types, no dice
         */
        if (typeA != typeB) {
            return false;
        }

        /*
         * For all other primitive types than numbers, trust JsonNode
         */
        if (!a.isContainerNode()) {
            return a.equals(b);
        }

        /*
         * OK, so they are containers (either both arrays or objects due to the
         * test on types above). They are obviously not equal if they do not
         * have the same number of elements/members.
         */
        if (a.size() != b.size()) {
            return false;
        }

        /*
         * Delegate to the appropriate method according to their type.
         */
        return typeA == JsonNodeType.ARRAY ? arrayEquals(a, b) : objectEquals(a, b);
    }

    @Override
    protected int doHash(final JsonNode t) {
        /*
         * If this is a numeric node, we want the same hashcode for the same
         * mathematical values. Go with double, its range is good enough for
         * 99+% of use cases.
         */
        if (t.isNumber()) {
            return Double.valueOf(t.doubleValue()).hashCode();
        }

        /*
         * If this is a primitive type (other than numbers, handled above),
         * delegate to JsonNode.
         */
        if (!t.isContainerNode()) {
            return t.hashCode();
        }

        /*
         * The following hash calculations work, yes, but they are poor at best.
         * And probably slow, too.
         *
         * TODO: try and figure out those hash classes from Guava
         */
        int ret = 0;

        /*
         * If the container is empty, just return
         */
        if (t.size() == 0) {
            return ret;
        }

        /*
         * Array
         */
        if (t.isArray()) {
            for (final JsonNode element : t) {
                ret = 31 * ret + doHash(element);
            }
            return ret;
        }

        /*
         * Not an array? An object.
         */
        final Iterator<Map.Entry<String, JsonNode>> iterator = t.fields();

        Map.Entry<String, JsonNode> entry;

        while (iterator.hasNext()) {
            entry = iterator.next();
            ret = 31 * ret + (entry.getKey().hashCode() ^ doHash(entry.getValue()));
        }

        return ret;
    }

    private static boolean numEquals(final JsonNode a, final JsonNode b) {
        /*
         * If both numbers are integers, delegate to JsonNode.
         */
        if (a.isIntegralNumber() && b.isIntegralNumber()) {
            return a.equals(b);
        }

        /*
         * Otherwise, compare decimal values.
         */
        return a.decimalValue().compareTo(b.decimalValue()) == 0;
    }

    private boolean arrayEquals(final JsonNode a, final JsonNode b) {
        /*
         * We are guaranteed here that arrays are the same size.
         */
        final int size = a.size();

        for (int i = 0; i < size; i++) {
            if (!doEquivalent(a.get(i), b.get(i))) {
                return false;
            }
        }

        return true;
    }

    private boolean objectEquals(final JsonNode a, final JsonNode b) {
        /*
         * Grab the key set from the first node
         */
        final Set<String> keys = Sets.newHashSet(a.fieldNames());

        /*
         * Grab the key set from the second node, and see if both sets are the
         * same. If not, objects are not equal, no need to check for children.
         */
        final Set<String> set = Sets.newHashSet(b.fieldNames());
        if (!set.equals(keys)) {
            return false;
        }

        /*
         * Test each member individually.
         */
        for (final String key : keys) {
            if (!doEquivalent(a.get(key), b.get(key))) {
                return false;
            }
        }

        return true;
    }
}
