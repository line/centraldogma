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
import { useRouter } from 'next/router';
import { XdsResourceType, XDS_RESOURCE_META } from 'dogma/features/xds/XdsTypes';

export type XdsSection = XdsResourceType | 'k8sAggregators' | 'credentials' | 'permissions' | 'dangerZone';

export interface XdsRoute {
  // The currently selected group, if any.
  group?: string;
  // The currently selected section in the sidebar (a resource type or 'permissions').
  section: XdsSection;
}

// Derives the current group and section from the router query. The group page uses `name` while the
// resource editor uses `group`; both carry an optional `type`.
export function useXdsRoute(): XdsRoute {
  const router = useRouter();
  const group = (router.query.name as string) || (router.query.group as string) || undefined;
  const type = router.query.type as string | undefined;
  const section: XdsSection =
    type === 'permissions' ||
    type === 'k8sAggregators' ||
    type === 'credentials' ||
    type === 'dangerZone' ||
    (type && type in XDS_RESOURCE_META)
      ? (type as XdsSection)
      : 'listeners';
  return { group, section };
}
