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

export type RepositoryRole = 'READ' | 'WRITE' | 'ADMIN';

export type ProjectRole = 'OWNER' | 'MEMBER' | 'GUEST';

export interface UserAndTimestamp {
  user: string;
  timestamp: string;
}

export interface MemberDto {
  login: string;
  role: ProjectRole;
}

export interface RepoRolesDto {
  projects: { member: RepositoryRole | null; guest: 'READ' | null };
  users: { [login: string]: RepositoryRole };
  appIds: { [appId: string]: RepositoryRole };
}

export interface RepositoryMetadataDto {
  name: string;
  roles: RepoRolesDto;
  creation: UserAndTimestamp;
  removal?: UserAndTimestamp;
}

export interface ProjectMetadataDto {
  name: string;
  repos: { [repoName: string]: RepositoryMetadataDto };
  members?: { [login: string]: MemberDto };
  creation: UserAndTimestamp;
}

// Payload for adding/deleting a user or app-id repository role.
export interface AddRepositoryRoleDto {
  group: string;
  data: { id: string; role: RepositoryRole };
}

export interface DeleteRepositoryRoleDto {
  group: string;
  id: string;
}
