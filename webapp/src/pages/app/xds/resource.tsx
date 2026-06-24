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
import { ResourceEditor } from 'dogma/features/xds/ResourceEditor';
import { XdsResourceType, XDS_RESOURCE_META } from 'dogma/features/xds/XdsTypes';

const ResourcePage = () => {
  const router = useRouter();
  const group = router.query.group as string;
  const type = router.query.type as XdsResourceType;
  const id = router.query.id as string | undefined;
  const isNew = router.query.action === 'new';
  const k8s = router.query.k8s === 'true';

  if (!group || !type || !XDS_RESOURCE_META[type]) {
    return null;
  }
  return <ResourceEditor group={group} type={type} id={id} isNew={isNew} k8s={k8s} />;
};

export default ResourcePage;
