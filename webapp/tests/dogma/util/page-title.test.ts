import fs from 'fs';
import path from 'path';
import { getPageTitleParts, toSiteTitle } from 'dogma/util/page-title';

const P = '/app/projects/[projectName]';
const R = `${P}/repos/[repoName]`;
const repoQuery = { projectName: 'myProj', repoName: 'myRepo' };

describe('getPageTitleParts', () => {
  it.each([
    ['/', {}, []],
    ['/web/auth/login', {}, ['Login']],
    ['/app/projects', {}, ['Projects']],
    [P, { projectName: 'myProj' }, ['myProj']],
    [`${P}/settings`, { projectName: 'myProj' }, ['Repositories', 'Settings', 'myProj']],
    [`${P}/settings/members`, { projectName: 'myProj' }, ['Members', 'Settings', 'myProj']],
    [
      `${P}/settings/credentials/[id]/edit`,
      { projectName: 'myProj', id: 'cred' },
      ['Edit cred', 'Credentials', 'Settings', 'myProj'],
    ],
    [`${P}/settings/variables/new`, { projectName: 'myProj' }, ['New', 'Variables', 'Settings', 'myProj']],
    [`${R}/tree/[revision]/[[...path]]`, { ...repoQuery, revision: 'head' }, ['myRepo', 'myProj']],
    [
      `${R}/tree/[revision]/[[...path]]`,
      { ...repoQuery, revision: 'head', path: ['a', 'b'] },
      ['/a/b', 'myRepo', 'myProj'],
    ],
    [`${R}/tree/[revision]/[[...path]]`, { ...repoQuery, revision: '42' }, ['Revision 42', 'myRepo', 'myProj']],
    [
      `${R}/files/[revision]/[[...path]]`,
      { ...repoQuery, revision: '42', path: ['a', 'config.json'] },
      ['/a/config.json@42', 'myRepo', 'myProj'],
    ],
    [`${R}/files/new/[[...path]]`, { ...repoQuery, path: ['a'] }, ['New file', 'myRepo', 'myProj']],
    [
      `${R}/commits/[[...path]]`,
      { ...repoQuery, path: ['config.json'] },
      ['History', '/config.json', 'myRepo', 'myProj'],
    ],
    [`${R}/commit/[revision]/[[...path]]`, { ...repoQuery, revision: '3' }, ['Commit 3', 'myRepo', 'myProj']],
    [
      `${R}/compare/[revision]/base/[baseRevision]`,
      { ...repoQuery, revision: '5', baseRevision: '2' },
      ['Changes 2..5', 'myRepo', 'myProj'],
    ],
    [`${R}/settings`, repoQuery, ['Roles', 'Settings', 'myRepo', 'myProj']],
    [
      `${R}/settings/mirrors/[id]`,
      { ...repoQuery, id: 'm1' },
      ['m1', 'Mirrors', 'Settings', 'myRepo', 'myProj'],
    ],
    ['/app/settings', {}, ['App Identities', 'Settings']],
    ['/app/settings/server-status', {}, ['Server Status', 'Settings']],
    ['/app/xds', {}, ['Groups', 'xDS']],
    ['/app/xds/control-plane', {}, ['Control Plane', 'xDS']],
    ['/app/xds/group', { name: 'g1', type: 'clusters' }, ['Clusters', 'g1', 'xDS']],
    ['/app/xds/group', { name: 'g1' }, ['Overview', 'g1', 'xDS']],
    ['/app/xds/resource', { group: 'g1', type: 'routes', id: 'r1' }, ['r1', 'Routes', 'g1', 'xDS']],
    ['/app/xds/resource', { group: 'g1', type: 'routes', action: 'new' }, ['New', 'Routes', 'g1', 'xDS']],
    ['/app/xds/k8s-aggregator', { group: 'g1', id: 'k1' }, ['k1', 'K8s Aggregators', 'g1', 'xDS']],
    ['/app/xds/mirrors/[id]/edit', { group: 'g1', id: 'm1' }, ['Edit m1', 'Mirroring', 'g1', 'xDS']],
    ['/app/xds/credentials/new', { group: 'g1' }, ['New', 'Credentials', 'g1', 'xDS']],
  ])('%s %j', (pathname, query, expected) => {
    expect(getPageTitleParts(pathname, query)).toEqual(expected);
  });

  it('gives every page a title', () => {
    const pagesDir = path.join(__dirname, '../../../src/pages');
    const routes = (fs.readdirSync(pagesDir, { recursive: true }) as string[])
      .filter((file) => file.endsWith('.tsx') && !path.basename(file).startsWith('_'))
      .map((file) => '/' + file.replace(/\.tsx$/, '').replace(/(^|\/)index$/, ''));
    const untitled = routes.filter((route) => {
      if (route === '/' || route === '/404') {
        return false;
      }
      // Fill the dynamic route parameters, e.g. '[projectName]' and '[[...path]]'.
      const query: Record<string, string | string[]> = {};
      for (const [, catchAll, name] of route.matchAll(/\[{1,2}(\.\.\.)?(\w+)\]{1,2}/g)) {
        query[name] = catchAll ? ['x'] : 'x';
      }
      const parts = getPageTitleParts(route, query);
      return parts.length === 0 || parts.some((part) => !part || part.includes('undefined'));
    });
    expect(routes.length).toBeGreaterThan(50);
    expect(untitled).toEqual([]);
  });
});

describe('toSiteTitle', () => {
  it('replaces the hostname placeholder', () => {
    expect(toSiteTitle({ title: 'Central Dogma at {{hostname}}', hostname: 'host1' })).toBe(
      'Central Dogma at host1',
    );
  });

  it('falls back to the default title', () => {
    expect(toSiteTitle(undefined)).toBe('Central Dogma');
  });
});
