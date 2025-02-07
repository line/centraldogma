/*
 * Copyright 2023 LINE Corporation
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

export type CredentialType = 'PASSWORD' | 'SSH_KEY' | 'ACCESS_TOKEN' | 'NONE';

export interface CredentialDto {
  id: string;
  name: string;
  type: CredentialType;

  // Password-based credential
  // - All fields are required.
  username?: string;
  password?: string;

  // Public key-based credential.
  // - `username` is also included in this credential.
  // - `passphrase` is optional and other fields are required.
  publicKey?: string;
  privateKey?: string;
  passphrase?: string;

  // Access token-based credential
  // - `accessToken` is required.
  accessToken?: string;
}

export interface CreateCredentialRequestDto {
  credentialId: string;
  credential: CredentialDto;
}

export function addIdFromCredentialName(credential: CredentialDto): CredentialDto & { id: string } {
  if (!credential) {
    return null;
  }
  return {
    ...credential,
    id: credential.name.split('/').pop() || '',
  };
}

export function addIdFromCredentialNames(credentials: CredentialDto[]): (CredentialDto & { id: string })[] {
  return credentials ? credentials.map(addIdFromCredentialName) : [];
}
