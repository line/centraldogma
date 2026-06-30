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
import { useGetMetadataQuery } from 'dogma/features/xds/xdsApiSlice';
import { ProjectMetadataDto, RepositoryRole } from 'dogma/features/xds/MetadataDto';
import { UserDto } from 'dogma/features/auth/UserDto';
import { useAppSelector } from 'dogma/hooks';

export interface GroupWriteAccess {
  // True while the metadata needed to determine the role is still being fetched.
  isLoading: boolean;
  // True only when the current user has the WRITE or ADMIN repository role on the group.
  hasWrite: boolean;
}

const RANK: Record<RepositoryRole, number> = { READ: 1, WRITE: 2, ADMIN: 3 };

function higher(a: RepositoryRole | null, b: RepositoryRole | null): RepositoryRole | null {
  if (!a) {
    return b;
  }
  if (!b) {
    return a;
  }
  return RANK[a] >= RANK[b] ? a : b;
}

/**
 * Resolves the current user's effective repository role on a group, mirroring the server's
 * {@code MetadataService.findRepositoryRole}: a system administrator is always ADMIN; otherwise the effective
 * role is the higher of the per-user role and the project member/guest role (an OWNER member is ADMIN).
 */
function effectiveRole(metadata: ProjectMetadataDto, group: string, user: UserDto): RepositoryRole | null {
  if (user.systemAdmin) {
    return 'ADMIN';
  }
  const repo = metadata.repos?.[group];
  if (!repo) {
    return null;
  }
  const roles = repo.roles;
  // The server keys per-user roles and members by User.id(), which is the email address.
  const perUserRole = roles.users?.[user.email] ?? null;
  const member = metadata.members?.[user.email];
  const projectRole = member ? member.role : 'GUEST';
  if (projectRole === 'OWNER') {
    return 'ADMIN';
  }
  const memberOrGuestRole = (projectRole === 'MEMBER' ? roles.projects?.member : roles.projects?.guest) ?? null;
  return higher(perUserRole, memberOrGuestRole);
}

/**
 * Determines whether the current user can modify (update/delete) a group's resources, i.e. has the WRITE or
 * ADMIN repository role. Used to hide mutating controls (e.g. the Delete button) from read-only users and from
 * users viewing an endpoint of a group they cannot write to. While loading or when the role cannot be
 * determined, write access is assumed absent so the controls stay hidden until access is confirmed.
 */
export function useGroupWriteAccess(group: string | undefined): GroupWriteAccess {
  const user = useAppSelector((state) => state.auth.user);
  const { data, isLoading, isUninitialized, isError } = useGetMetadataQuery(undefined, { skip: !group });
  if (!group || !user) {
    return { isLoading: false, hasWrite: false };
  }
  if (isLoading || isUninitialized) {
    return { isLoading: true, hasWrite: false };
  }
  if (isError || !data) {
    return { isLoading: false, hasWrite: false };
  }
  const role = effectiveRole(data, group, user);
  return { isLoading: false, hasWrite: role === 'WRITE' || role === 'ADMIN' };
}
