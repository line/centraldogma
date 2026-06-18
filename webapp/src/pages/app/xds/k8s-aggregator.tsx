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
import { K8sAggregatorEditor } from 'dogma/features/xds/K8sAggregatorEditor';

const K8sAggregatorPage = () => {
  const router = useRouter();
  const group = router.query.group as string;
  const id = router.query.id as string | undefined;
  const isNew = router.query.action === 'new';

  if (!group) {
    return null;
  }
  return <K8sAggregatorEditor group={group} id={id} isNew={isNew} />;
};

export default K8sAggregatorPage;
