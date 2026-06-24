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
import { useGetGroupsQuery } from 'dogma/features/xds/xdsApiSlice';

export interface GroupExists {
  // True while the group list is still being fetched.
  isLoading: boolean;
  // Whether a group with the given id exists. Optimistically true while loading to avoid a flash of the
  // "not found" message.
  exists: boolean;
}

/**
 * Determines whether a group exists by checking it against the list of groups. The resource list APIs treat a
 * missing repository the same as an empty one (a 404 is mapped to an empty list), so a non-existent group would
 * otherwise render as an empty, valid-looking group; this hook lets callers detect and surface that instead.
 */
export function useGroupExists(group: string | undefined): GroupExists {
  // Don't fetch the group list until a group is actually selected.
  const { data, isLoading, isUninitialized, isError } = useGetGroupsQuery(undefined, { skip: !group });
  if (!group) {
    return { isLoading: false, exists: false };
  }
  if (isLoading || isUninitialized) {
    return { isLoading: true, exists: true };
  }
  // Only conclude a group is missing when the list was fetched successfully and does not contain it. If the
  // fetch failed (error, or no data), assume the group exists so a valid group is not misrendered as
  // "not found" on a transient/permission/network error.
  if (isError || !data) {
    return { isLoading: false, exists: true };
  }
  return { isLoading: false, exists: data.some((g) => g.id === group) };
}
