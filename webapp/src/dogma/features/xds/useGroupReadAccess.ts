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
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { useListResourcesQuery } from 'dogma/features/xds/xdsApiSlice';

export interface GroupReadAccess {
  // True while the access check is still in flight.
  isLoading: boolean;
  // True unless the server explicitly rejected the access-controlled read with 403 Forbidden.
  hasAccess: boolean;
}

/**
 * Determines whether the current user can read the access-controlled resources (clusters / listeners / routes)
 * of a group. Instead of replicating the server's role resolution on the client, it probes one access-controlled
 * list API (clusters) and treats a 403 response as "no access". Endpoints are intentionally excluded because
 * they are readable by everyone regardless of permission.
 *
 * The probe reuses the same RTK Query cache entry as the Clusters view, so it does not add a redundant request
 * when that view is opened.
 */
export function useGroupReadAccess(group: string | undefined): GroupReadAccess {
  const { error, isLoading, isUninitialized } = useListResourcesQuery(
    { group: group as string, type: 'clusters' },
    { skip: !group },
  );
  if (!group) {
    return { isLoading: false, hasAccess: true };
  }
  const forbidden = !!error && (error as FetchBaseQueryError).status === 403;
  return { isLoading: isLoading || isUninitialized, hasAccess: !forbidden };
}
