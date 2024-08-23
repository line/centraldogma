/*
 * Copyright 2024 LINE Corporation
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

export function toFilePath(path: string[] | string): string {
  if (typeof path === 'string') {
    return '/' + path;
  }

  if (!path || path.length === 0) {
    return '/';
  }
  return '/' + path.join('/').replace(/\/\//g, '/');
}

export type UrlAndSegment = {
  segment: string;
  url: string;
};

export function makeTraversalFileLinks(projectName: string, repoName: string, path: string): UrlAndSegment[] {
  const links: UrlAndSegment[] = [];
  const segments = path.split('/');
  for (let i = 1; i < segments.length; i++) {
    const url = `/app/projects/${projectName}/repos/${repoName}/tree/head/${segments.slice(1, i + 1).join('/')}`;
    links.push({ segment: segments[i], url });
  }
  return links;
}

