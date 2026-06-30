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
import { useListCredentialsQuery } from 'dogma/features/xds/xdsApiSlice';

export interface GroupAdminAccess {
  // True while the check is still in flight.
  isLoading: boolean;
  // True only when the current user has the ADMIN repository role on the group.
  isAdmin: boolean;
}

/**
 * Determines whether the current user has the ADMIN role on a group. Rather than replicating the server's role
 * resolution on the client, it probes an ADMIN-only API (the repository credential list); a successful response
 * means ADMIN, a 403 means not. Deleting a group requires the same ADMIN role, so this gates the delete action.
 *
 * The probe reuses the same RTK Query cache entry as the Credentials tab.
 */
export function useGroupAdminAccess(group: string | undefined): GroupAdminAccess {
  const { error, isLoading, isUninitialized } = useListCredentialsQuery(
    { group: group as string },
    { skip: !group },
  );
  if (!group) {
    return { isLoading: false, isAdmin: false };
  }
  return { isLoading: isLoading || isUninitialized, isAdmin: !isLoading && !isUninitialized && !error };
}
