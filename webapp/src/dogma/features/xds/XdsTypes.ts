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

// The internal Central Dogma project that backs every xDS group.
export const XDS_PROJECT = '@xds';

// xDS resource types. Each value is also the repository directory name and the
// path segment used by the xDS HTTP API (e.g. /api/v1/xds/groups/{group}/clusters).
export type XdsResourceType = 'listeners' | 'routes' | 'clusters' | 'endpoints';

export const XDS_RESOURCE_TYPES: XdsResourceType[] = ['listeners', 'routes', 'clusters', 'endpoints'];

export interface XdsResourceTypeMeta {
  type: XdsResourceType;
  // Human readable, singular label.
  label: string;
  // The xDS acronym (LDS/RDS/CDS/EDS).
  acronym: string;
  // The query parameter name used when creating a resource (e.g. cluster_id).
  idParam: string;
}

export const XDS_RESOURCE_META: Record<XdsResourceType, XdsResourceTypeMeta> = {
  listeners: { type: 'listeners', label: 'Listener', acronym: 'LDS', idParam: 'listener_id' },
  routes: { type: 'routes', label: 'Route', acronym: 'RDS', idParam: 'route_id' },
  clusters: { type: 'clusters', label: 'Cluster', acronym: 'CDS', idParam: 'cluster_id' },
  endpoints: { type: 'endpoints', label: 'Endpoint', acronym: 'EDS', idParam: 'endpoint_id' },
};

// A group corresponds to a repository under the '@xds' project.
export interface GroupDto {
  // The group id, which equals the backing repository name.
  id: string;
}

export interface XdsResourceDto {
  // The resource id, i.e. the file path under the type directory without the
  // leading '/{type}/' prefix and the trailing file extension (.yaml or .json).
  id: string;
  // The full repository path, e.g. '/clusters/foo.yaml'.
  path: string;
  revision: number;
}

// The full xDS resource name derived from its repository path, e.g. group 'foo' + '/clusters/c1.yaml'
// becomes 'groups/foo/clusters/c1'. This matches the `name` the server assigns to CDS/LDS/RDS resources.
export function resourceName(group: string, path: string): string {
  return `groups/${group}${path.replace(/\.yaml$/, '')}`;
}

// A starter template offered when creating a new resource of each type.
// The `name` field (for LDS/RDS/CDS) and `clusterName` field (for EDS) are injected by the server,
// so they are intentionally omitted here.
export const XDS_RESOURCE_TEMPLATES: Record<XdsResourceType, string> = {
  listeners: `apiListener:
  apiListener:
    '@type': 'type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager'
    rds:
      configSource:
        ads: {}
        resourceApiVersion: V3
      routeConfigName: ''
`,
  routes: `virtualHosts:
  - name: ''
    domains:
      - '*'
    routes:
      - match:
          prefix: /
        route:
          cluster: ''
`,
  clusters: `type: EDS
edsClusterConfig:
  edsConfig:
    ads: {}
    resourceApiVersion: V3
  serviceName: ''
healthChecks:
  - interval: 5s
    httpHealthCheck:
      path: /
`,
  endpoints: `endpoints:
  - locality:
      zone: ''
    lbEndpoints:
      - endpoint:
          address:
            socketAddress:
              address: ''
              portValue: 0
          hostname: ''
        healthStatus: HEALTHY
        loadBalancingWeight: 1000
`,
};
