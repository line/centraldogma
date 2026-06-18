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
import { UserAndTimestamp } from 'dogma/features/xds/MetadataDto';

export type AppIdentityType = 'TOKEN' | 'CERTIFICATE';

export interface AppIdentity {
  appId: string;
  type: AppIdentityType;
  systemAdmin: boolean;
  allowGuestAccess: boolean;
  creation: UserAndTimestamp;
  deactivation?: UserAndTimestamp;
  deletion?: UserAndTimestamp;
}

export interface Token extends AppIdentity {
  type: 'TOKEN';
  secret?: string;
}

export interface CertificateAppIdentity extends AppIdentity {
  type: 'CERTIFICATE';
  certificateId: string;
}

export type AppIdentityDto = Token | CertificateAppIdentity;

export const isToken = (identity: AppIdentity): identity is Token => identity.type === 'TOKEN';

export const isCertificate = (identity: AppIdentity): identity is CertificateAppIdentity =>
  identity.type === 'CERTIFICATE';
