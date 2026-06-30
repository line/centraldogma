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

export type CredentialType = 'PASSWORD' | 'SSH_KEY' | 'ACCESS_TOKEN' | 'NONE';

// A credential as returned by the server. Secrets are masked for non-system-admins.
export interface CredentialDto {
  // The full credential name, e.g. 'projects/@xds/repos/{group}/credentials/{id}'.
  name: string;
  type: CredentialType;
  accessToken?: string;
}

// The credential id is the last path segment of its name.
export interface XdsCredentialDto {
  id: string;
  type: CredentialType;
}

export function credentialId(name: string): string {
  return name.split('/').pop() || '';
}
