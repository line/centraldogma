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
import * as jsYaml from 'js-yaml';
import { XdsResourceType } from 'dogma/features/xds/XdsTypes';

// A reference from one xDS resource to a child resource of another type:
//  - a Listener (LDS) references Routes (RDS) and Clusters (CDS)
//  - a Route (RDS) references Clusters (CDS)
//  - a Cluster (CDS) references an Endpoint (EDS)
export interface XdsReference {
  // The type of the referenced (child) resource.
  targetType: XdsResourceType;
  // The raw reference value found in the config (typically a full resource name such as
  // 'groups/{group}/clusters/{id}', but may be any string the user wrote).
  name: string;
}

// A reference resolved to the parameters needed to navigate to the child resource's view page.
export interface XdsReferenceLink extends XdsReference {
  // The group the child resource lives in (parsed from the name, else the current group).
  group: string;
  // The child resource id used by the resource page.
  id: string;
  // Whether the child is a Kubernetes-aggregator-generated resource (served from the '/k8s/' path).
  k8s: boolean;
}

function isObject(node: unknown): node is Record<string, unknown> {
  return !!node && typeof node === 'object' && !Array.isArray(node);
}

// Visits every plain object nested anywhere in the tree.
function walk(node: unknown, visit: (obj: Record<string, unknown>) => void): void {
  if (Array.isArray(node)) {
    node.forEach((n) => walk(n, visit));
  } else if (isObject(node)) {
    visit(node);
    Object.values(node).forEach((v) => walk(v, visit));
  }
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

// Extracts the child-resource references declared in a resource's YAML (proto3 field names in
// lowerCamelCase or snake_case are both accepted by the server, so both spellings are checked).
export function extractReferences(type: XdsResourceType, content: string): XdsReference[] {
  let json: unknown;
  try {
    json = jsYaml.load(content);
  } catch {
    return [];
  }

  const refs: XdsReference[] = [];
  const add = (targetType: XdsResourceType, name: string | undefined) => {
    if (name) {
      refs.push({ targetType, name });
    }
  };

  // Both listeners (through inline route configs / tcp_proxy) and routes point at clusters.
  if (type === 'listeners' || type === 'routes') {
    walk(json, (obj) => {
      add('clusters', asString(obj.cluster));
      const weighted = obj.weightedClusters ?? obj.weighted_clusters;
      if (isObject(weighted) && Array.isArray(weighted.clusters)) {
        weighted.clusters.forEach((c) => add('clusters', asString(isObject(c) ? c.name : undefined)));
      }
    });
  }

  // Listeners reference route configurations through the HTTP connection manager's RDS config.
  if (type === 'listeners') {
    walk(json, (obj) => {
      const rds = obj.rds;
      if (isObject(rds)) {
        add('routes', asString(rds.routeConfigName ?? rds.route_config_name));
      }
    });
  }

  // A cluster of type EDS references a ClusterLoadAssignment (EDS) by its EDS service name; when the
  // service name is omitted, Envoy uses the cluster's own name.
  if (type === 'clusters') {
    let edsServiceFound = false;
    walk(json, (obj) => {
      const eds = obj.edsClusterConfig ?? obj.eds_cluster_config;
      if (isObject(eds)) {
        const serviceName = asString(eds.serviceName ?? eds.service_name);
        if (serviceName) {
          edsServiceFound = true;
          add('endpoints', serviceName);
        }
      }
    });
    if (!edsServiceFound && isObject(json) && (json.type === 'EDS' || json.type === 'eds')) {
      add('endpoints', asString(json.name));
    }
  }

  // Dedupe identical references.
  const seen = new Set<string>();
  return refs.filter((ref) => {
    const key = `${ref.targetType}	${ref.name}`;
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

const GROUPS_NAME = /^groups\/([^/]+)\/(.+)$/;

// Resolves a reference to the navigation parameters of the child resource's view page. The child type is
// always taken from the reference's context (e.g. a cluster always points at an endpoint), while the group,
// id and k8s flag are parsed from the resource name when it follows the 'groups/{group}/.../{id}' convention.
// Names that do not follow the convention are assumed to be a bare id within the current group.
export function resolveReference(currentGroup: string, ref: XdsReference): XdsReferenceLink {
  const match = GROUPS_NAME.exec(ref.name);
  if (match) {
    const group = match[1];
    const segments = match[2].split('/');
    // segments[0] is the resource type (e.g. 'clusters', 'routes', 'endpoints', 'listeners').
    // For k8s endpoints the path starts with 'k8s/{type}/{id...}', otherwise '{type}/{id...}'.
    // The id is everything after the type prefix and may itself contain '/' characters.
    const k8s = segments[0] === 'k8s';
    const idStart = k8s ? 2 : 1;
    const id = segments.slice(idStart).join('/');
    return {
      ...ref,
      group,
      id,
      k8s,
    };
  }
  return { ...ref, group: currentGroup, id: ref.name, k8s: false };
}

// The resolution status of a reference edge within a group:
//  - 'ok'       : the referenced resource exists in this group
//  - 'missing'  : the referenced resource is in this group's namespace but does not exist (dangling)
//  - 'external' : the reference points at another group, so it cannot be verified here
export type XdsRefStatus = 'ok' | 'missing' | 'external';

// A node in a group's reference graph (one xDS resource).
export interface XdsGraphNode {
  type: XdsResourceType;
  id: string;
  // The full resource name, e.g. 'groups/{group}/clusters/{id}'.
  name: string;
  // Whether it is a Kubernetes-aggregator-generated resource (served from the '/k8s/' path).
  k8s: boolean;
}

// A directed reference edge from one resource to a referenced (child) resource.
export interface XdsGraphEdge {
  fromType: XdsResourceType;
  fromId: string;
  fromName: string;
  targetType: XdsResourceType;
  targetId: string;
  targetGroup: string;
  targetK8s: boolean;
  // The raw reference value as written in the config.
  name: string;
  status: XdsRefStatus;
}

export interface XdsReferenceGraph {
  nodes: XdsGraphNode[];
  edges: XdsGraphEdge[];
}

// The resource view page URL for a resolved reference.
export function referenceHref(link: XdsReferenceLink): string {
  const params = new URLSearchParams({ group: link.group, type: link.targetType, id: link.id });
  if (link.k8s) {
    params.set('k8s', 'true');
  }
  return `/app/xds/resource?${params.toString()}`;
}
