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
import { resolveReference } from 'dogma/features/xds/xdsReferences';

describe('resolveReference', () => {
  const cdsRef = (name: string) => ({ targetType: 'clusters' as const, name });

  describe('groups/{group}/{type}/{id} convention', () => {
    it('resolves a simple (non-nested) resource id', () => {
      const result = resolveReference('current', cdsRef('groups/dx/clusters/my-cluster'));
      expect(result).toMatchObject({ group: 'dx', id: 'my-cluster', k8s: false });
    });

    it('preserves slashes in a multi-segment resource id', () => {
      // regression: previously only the last path segment was captured
      const result = resolveReference('current', cdsRef('groups/dx/clusters/centraldogma-oss-mdc/alpha'));
      expect(result).toMatchObject({ group: 'dx', id: 'centraldogma-oss-mdc/alpha', k8s: false });
    });

    it('preserves three-segment resource ids', () => {
      const result = resolveReference('current', cdsRef('groups/g1/endpoints/a/b/c'));
      expect(result).toMatchObject({ group: 'g1', id: 'a/b/c', k8s: false });
    });
  });

  describe('k8s path (groups/{group}/k8s/{type}/{id})', () => {
    it('detects k8s flag and strips the k8s+type prefix', () => {
      const result = resolveReference('current', {
        targetType: 'endpoints',
        name: 'groups/g1/k8s/endpoints/my-ep',
      });
      expect(result).toMatchObject({ group: 'g1', id: 'my-ep', k8s: true });
    });

    it('preserves slashes in a k8s multi-segment id', () => {
      const result = resolveReference('current', {
        targetType: 'endpoints',
        name: 'groups/g1/k8s/endpoints/ns/my-ep',
      });
      expect(result).toMatchObject({ group: 'g1', id: 'ns/my-ep', k8s: true });
    });
  });

  describe('bare name fallback', () => {
    it('uses current group and raw name when not in groups/ convention', () => {
      const result = resolveReference('mygroup', cdsRef('bare-cluster'));
      expect(result).toMatchObject({ group: 'mygroup', id: 'bare-cluster', k8s: false });
    });
  });
});
