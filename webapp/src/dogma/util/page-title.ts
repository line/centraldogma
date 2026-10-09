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
import { ParsedUrlQuery } from 'querystring';
import { TitleDto } from 'dogma/features/api/apiSlice';
import { toXdsSection, xdsSectionTitle, XdsSection } from 'dogma/features/xds/useXdsRoute';

export function toSiteTitle(titleDto: TitleDto | undefined): string {
  return titleDto?.title.replace('{{hostname}}', titleDto.hostname) || 'Central Dogma';
}

const isPresent = (part: string | undefined): part is string => !!part;

// 'danger-zone' -> 'Danger Zone'
const toLabel = (slug: string) =>
  slug
    .split('-')
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ');

// The part for the '[id]', '[id]/edit' and 'new' sub-pages.
function itemPart([sub, action]: string[], query: ParsedUrlQuery): string | undefined {
  if (sub === 'new' || query.action === 'new') {
    return 'New';
  }
  return action === 'edit' ? `Edit ${query.id}` : (query.id as string | undefined);
}

function settingsParts([section, ...rest]: string[], query: ParsedUrlQuery, defaultSection: string) {
  return [itemPart(rest, query), section ? toLabel(section) : defaultSection, 'Settings'].filter(isPresent);
}

function repoParts([page, ...rest]: string[], query: ParsedUrlQuery): string[] {
  const revision = query.revision as string;
  const name = (query.path as string[] | undefined)?.at(-1);
  switch (page) {
    case 'tree':
    case 'files': {
      if (rest[0] === 'new') {
        return ['New file'];
      }
      const file = name && page === 'tree' ? `${name}/` : name;
      if (revision === 'head') {
        return file ? [file] : [];
      }
      return [file ? `${file}@${revision}` : `Revision ${revision}`];
    }
    case 'commits':
      return ['History', name].filter(isPresent);
    case 'commit':
      return [`Commit ${revision}`];
    case 'compare':
      return [`Changes ${query.baseRevision}..${revision}`];
    case 'settings':
      return settingsParts(rest, query, 'Roles');
    default:
      return [];
  }
}

function xdsParts([page, ...rest]: string[], query: ParsedUrlQuery): string[] {
  if (!page) {
    return ['Groups', 'xDS'];
  }
  if (page === 'control-plane') {
    return ['Control Plane', 'xDS'];
  }
  const section: XdsSection =
    page === 'k8s-aggregator'
      ? 'k8sAggregators'
      : page === 'mirrors'
        ? 'mirroring'
        : page === 'credentials'
          ? 'credentials'
          : toXdsSection(query.type as string | undefined);
  // The group page uses `name` while the other pages use `group`.
  const group = (query.name ?? query.group) as string | undefined;
  return [itemPart(rest, query), xdsSectionTitle(section), group, 'xDS'].filter(isPresent);
}

// Returns the title parts of a page, most specific first. `pathname` is the route pattern such as
// '/app/projects/[projectName]'.
export function getPageTitleParts(pathname: string, query: ParsedUrlQuery): string[] {
  if (pathname === '/web/auth/login') {
    return ['Login'];
  }
  const [root, area, ...rest] = pathname.split('/').filter((s) => s.length > 0);
  if (root !== 'app') {
    return [];
  }
  if (area === 'settings') {
    return settingsParts(rest, query, 'App Identities');
  }
  if (area === 'xds') {
    return xdsParts(rest, query);
  }
  if (rest.length === 0) {
    return ['Projects'];
  }
  // rest: ['[projectName]', 'settings' | 'repos', '[repoName]', ...]
  const projectName = query.projectName as string;
  if (rest[1] === 'settings') {
    return [...settingsParts(rest.slice(2), query, 'Repositories'), projectName];
  }
  if (rest[2] === '[repoName]') {
    return [...repoParts(rest.slice(3), query), query.repoName as string, projectName];
  }
  return [projectName];
}
