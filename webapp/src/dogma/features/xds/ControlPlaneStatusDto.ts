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

import { XdsResourceType } from 'dogma/features/xds/XdsTypes';

export type XdsAckStatus = 'INITIAL' | 'ACKED' | 'NACKED';

// The ACK/NACK state of one resource type on a single discovery stream, as exposed by
// GET /api/v1/xds/clients.
export interface XdsClientTypeStatus {
  // The xDS type URL, e.g. 'type.googleapis.com/envoy.config.listener.v3.Listener'.
  typeUrl: string;
  status: XdsAckStatus;
  // The version the client last ACKed (empty until the first ACK).
  ackedVersion: string;
  // The version this stream was last sent (empty until the first response).
  servedVersion: string;
  // True when the client has ACKed exactly the version currently being served.
  inSync: boolean;
  // The reason Envoy rejected the resources; only set when status is 'NACKED'.
  nackReason: string;
  lastNonce: string;
  // Epoch millis of the last request observed for this type.
  lastSeen: number;
  // The resource names the client subscribed to. An empty array means wildcard (all resources).
  resourceNames: string[];
}

// One discovery stream connected to this control plane instance.
export interface XdsClientStatus {
  streamId: number;
  // The node id reported by the client (populated from its first request).
  nodeId: string;
  nodeCluster: string;
  // The authenticated application identity the served snapshot is scoped by; empty for anonymous clients
  // served the full snapshot.
  appId: string;
  // Epoch millis when the stream opened.
  openedAt: number;
  types: XdsClientTypeStatus[];
}

// An application identity that has connected to the discovery API, with the groups it can read.
export interface XdsApp {
  appId: string;
  readableGroups: string[];
}

// The serving snapshot of a single resource type, as exposed by GET /api/v1/xds/snapshot.
export interface XdsSnapshotType {
  // The version Envoy ACKs for this type.
  version: string;
  // Resource name -> the serialized resource (Envoy proto rendered as JSON).
  resources: Record<string, unknown>;
}

export interface XdsSnapshot {
  // Present only when the snapshot was scoped to a group.
  group?: string;
  // Present only when the snapshot was scoped to an application identity.
  appId?: string;
  // For an app-scoped snapshot: the groups the app can read, and whether anything is currently served to it
  // (false when the app id has never connected).
  readableGroups?: string[];
  served?: boolean;
  listeners?: XdsSnapshotType;
  routes?: XdsSnapshotType;
  clusters?: XdsSnapshotType;
  endpoints?: XdsSnapshotType;
}

// Maps an xDS type URL to its acronym (LDS/RDS/CDS/EDS) and the resource type used by the snapshot/repo APIs.
// Returns the raw type URL as the acronym for any unrecognized type.
export function xdsTypeOf(typeUrl: string): { acronym: string; type?: XdsResourceType } {
  if (typeUrl.endsWith('ClusterLoadAssignment')) {
    return { acronym: 'EDS', type: 'endpoints' };
  }
  if (typeUrl.endsWith('RouteConfiguration')) {
    return { acronym: 'RDS', type: 'routes' };
  }
  if (typeUrl.endsWith('Listener')) {
    return { acronym: 'LDS', type: 'listeners' };
  }
  if (typeUrl.endsWith('Cluster')) {
    return { acronym: 'CDS', type: 'clusters' };
  }
  return { acronym: typeUrl };
}
