/*
 * Copyright 2022 LINE Corporation
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

import { RepoDto } from 'dogma/features/repo/RepoDto';
import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';
import { ProjectDto } from 'dogma/features/project/ProjectDto';
import { AuthState } from 'dogma/features/auth/authSlice';
import { FileDto } from 'dogma/features/file/FileDto';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import { ProjectMetadataDto } from 'dogma/features/project/ProjectMetadataDto';
import { FileContentDto } from 'dogma/features/file/FileContentDto';
import { RevisionDto } from 'dogma/features/history/RevisionDto';
import { TokenDto } from 'dogma/features/token/TokenDto';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { DeleteUserPermissionDto } from 'dogma/features/repo/permissions/DeleteUserPermissionDto';
import { AddUserPermissionDto } from 'dogma/features/repo/permissions/AddUserPermissionDto';
import { DeleteMemberDto } from 'dogma/features/project/settings/members/DeleteMemberDto';
import { MirrorDto } from 'dogma/features/project/settings/mirrors/MirrorDto';
import { CredentialDto } from 'dogma/features/project/settings/credentials/CredentialDto';
import { MirrorResult } from '../mirror/MirrorResult';

export type ApiAction<Arg, Result> = {
  (arg: Arg): { unwrap: () => Promise<Result> };
};

export type GetProjects = {
  admin: boolean;
};

export type GetHistory = {
  projectName: string;
  repoName: string;
  revision: string | number;
  filePath: string;
  to?: number;
  maxCommits?: number;
};

export type GetNormalisedRevision = {
  projectName: string;
  repoName: string;
  revision: number;
};

export type GetFilesByProjectAndRepoName = {
  projectName: string;
  repoName: string;
  revision?: string | number;
  filePath?: string;
  withContent: boolean;
};

export type GetFileContent = {
  projectName: string;
  repoName: string;
  filePath: string;
  revision: string | number;
};

export type TitleDto = {
  title: string;
  hostname: string;
};

export const apiSlice = createApi({
  reducerPath: 'api',
  baseQuery: fetchBaseQuery({
    baseUrl: `${process.env.NEXT_PUBLIC_HOST || ''}/`,
    prepareHeaders: (headers, { getState, type }) => {
      const { auth } = getState() as { auth: AuthState };
      headers.set('Authorization', `Bearer ${auth?.sessionId || 'anonymous'}`);
      if (type === 'mutation' && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json; charset=UTF-8');
      }
      return headers;
    },
  }),
  tagTypes: ['Project', 'Metadata', 'Repo', 'File', 'Token'],
  endpoints: (builder) => ({
    getProjects: builder.query<ProjectDto[], GetProjects>({
      async queryFn(arg, _queryApi, _extraOptions, fetchWithBQ) {
        const projects = await fetchWithBQ('/api/v1/projects');
        if (projects.error) return { error: projects.error as FetchBaseQueryError };
        if (arg.admin) {
          const removedProjects = await fetchWithBQ('/api/v1/projects?status=removed');
          if (removedProjects.error) return { error: removedProjects.error as FetchBaseQueryError };
          return {
            data: [
              ...((projects.data || []) as ProjectDto[]),
              ...((removedProjects.data || []) as ProjectDto[]),
            ],
          };
        }
        return { data: (projects.data || []) as ProjectDto[] };
      },
      providesTags: ['Project'],
    }),
    addNewProject: builder.mutation({
      query: (payload) => ({
        url: `/api/v1/projects`,
        method: 'POST',
        body: payload,
      }),
      invalidatesTags: ['Project'],
    }),
    deleteProject: builder.mutation({
      query: ({ projectName }) => ({
        url: `/api/v1/projects/${projectName}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Project'],
    }),
    restoreProject: builder.mutation({
      query: ({ projectName }) => ({
        url: `/api/v1/projects/${projectName}`,
        method: 'PATCH',
        headers: {
          'Content-type': 'application/json-patch+json; charset=UTF-8',
        },
        body: [{ op: 'replace', path: '/status', value: 'active' }],
      }),
      invalidatesTags: ['Project'],
    }),
    getMetadataByProjectName: builder.query<ProjectMetadataDto, string>({
      query: (projectName) => `/api/v1/projects/${projectName}`,
      providesTags: ['Repo', 'Metadata'],
    }),
    addNewMember: builder.mutation({
      query: ({ projectName, id, role }) => ({
        url: `/api/v1/metadata/${projectName}/members`,
        method: 'POST',
        body: { id, role: role.toUpperCase() },
      }),
      invalidatesTags: ['Metadata'],
    }),
    deleteMember: builder.mutation<void, DeleteMemberDto>({
      query: ({ projectName, id }) => ({
        url: `/api/v1/metadata/${projectName}/members/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Metadata'],
    }),
    addNewTokenMember: builder.mutation({
      query: ({ projectName, id, role }) => ({
        url: `/api/v1/metadata/${projectName}/tokens`,
        method: 'POST',
        body: { id, role: role.toUpperCase() },
      }),
      invalidatesTags: ['Metadata'],
    }),
    deleteTokenMember: builder.mutation<void, DeleteMemberDto>({
      query: ({ projectName, id }) => ({
        url: `/api/v1/metadata/${projectName}/tokens/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Metadata'],
    }),
    updateRolePermission: builder.mutation({
      query: ({ projectName, repoName, data }) => ({
        url: `/api/v1/metadata/${projectName}/repos/${repoName}/perm/role`,
        method: 'POST',
        body: data,
      }),
      invalidatesTags: ['Metadata'],
    }),
    addUserPermission: builder.mutation<void, AddUserPermissionDto>({
      query: ({ projectName, repoName, data }) => ({
        url: `/api/v1/metadata/${projectName}/repos/${repoName}/perm/users`,
        method: 'POST',
        body: data,
      }),
      invalidatesTags: ['Metadata'],
    }),
    deleteUserPermission: builder.mutation<void, DeleteUserPermissionDto>({
      query: ({ projectName, repoName, id }) => ({
        url: `/api/v1/metadata/${projectName}/repos/${repoName}/perm/users/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Metadata'],
    }),
    addTokenPermission: builder.mutation<void, AddUserPermissionDto>({
      query: ({ projectName, repoName, data }) => ({
        url: `/api/v1/metadata/${projectName}/repos/${repoName}/perm/tokens`,
        method: 'POST',
        body: data,
      }),
      invalidatesTags: ['Metadata'],
    }),
    deleteTokenPermission: builder.mutation<void, DeleteUserPermissionDto>({
      query: ({ projectName, repoName, id }) => ({
        url: `/api/v1/metadata/${projectName}/repos/${repoName}/perm/tokens/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Metadata'],
    }),
    getRepos: builder.query<RepoDto[], string>({
      query: (projectName) => `/api/v1/projects/${projectName}/repos`,
      providesTags: ['Repo'],
    }),
    addNewRepo: builder.mutation({
      query: ({ projectName, data }) => ({
        url: `/api/v1/projects/${projectName}/repos`,
        method: 'POST',
        body: data,
      }),
      invalidatesTags: ['Repo'],
    }),
    deleteRepo: builder.mutation({
      query: ({ projectName, repoName }) => ({
        url: `/api/v1/projects/${projectName}/repos/${repoName}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Repo'],
    }),
    restoreRepo: builder.mutation({
      query: ({ projectName, repoName }) => ({
        url: `/api/v1/projects/${projectName}/repos/${repoName}`,
        method: 'PATCH',
        headers: {
          'Content-type': 'application/json-patch+json; charset=UTF-8',
        },
        body: [{ op: 'replace', path: '/status', value: 'active' }],
      }),
      invalidatesTags: ['Repo'],
    }),
    getFiles: builder.query<FileDto[] | FileDto, GetFilesByProjectAndRepoName>({
      query: ({ projectName, repoName, revision, filePath, withContent }) => {
        if (withContent) {
          return `/api/v1/projects/${projectName}/repos/${repoName}/contents${filePath || ''}?revision=${revision || 'head'}`;
        } else {
          return `/api/v1/projects/${projectName}/repos/${repoName}/list${filePath || ''}?revision=${revision || 'head'}`;
        }
      },
      providesTags: ['File'],
    }),
    getFileContent: builder.query<FileContentDto, GetFileContent>({
      query: ({ projectName, repoName, filePath, revision }) =>
        `/api/v1/projects/${projectName}/repos/${repoName}/contents${filePath}?revision=${revision}`,
      providesTags: ['File'],
    }),
    pushFileChanges: builder.mutation({
      query: ({ projectName, repoName, data }) => ({
        url: `/api/v1/projects/${projectName}/repos/${repoName}/contents`,
        method: 'POST',
        body: data,
      }),
      invalidatesTags: ['File'],
    }),
    getHistory: builder.query<HistoryDto[], GetHistory>({
      query: function ({ projectName, repoName, revision, filePath, to, maxCommits }) {
        let path = `/api/v1/projects/${projectName}/repos/${repoName}/commits/${revision}?path=${filePath || '/**'}`;
        if (to) {
          path += `&to=${to}`;
        }
        if (maxCommits) {
          path += `&maxCommits=${maxCommits}`;
        }
        return path;
      },
      providesTags: ['File'],
    }),
    getNormalisedRevision: builder.query<RevisionDto, GetNormalisedRevision>({
      query: ({ projectName, repoName, revision }) =>
        `/api/v1/projects/${projectName}/repos/${repoName}/revision/${revision}`,
      providesTags: ['File'],
    }),
    getTokens: builder.query<TokenDto[], void>({
      query: () => '/api/v1/tokens',
      providesTags: ['Token'],
    }),
    addNewToken: builder.mutation({
      query: ({ data }) => ({
        url: `/api/v1/tokens`,
        method: 'POST',
        body: data,
        headers: {
          'Content-type': 'application/x-www-form-urlencoded; charset=UTF-8',
        },
      }),
      invalidatesTags: ['Token'],
    }),
    deactivateToken: builder.mutation({
      query: ({ appId }) => ({
        url: `/api/v1/tokens/${appId}`,
        method: 'PATCH',
        body: [{ op: 'replace', path: '/status', value: 'inactive' }],
        headers: {
          'Content-type': 'application/json-patch+json; charset=UTF-8',
        },
      }),
      invalidatesTags: ['Token'],
    }),
    activateToken: builder.mutation({
      query: ({ appId }) => ({
        url: `/api/v1/tokens/${appId}`,
        method: 'PATCH',
        body: [{ op: 'replace', path: '/status', value: 'active' }],
        headers: {
          'Content-type': 'application/json-patch+json; charset=UTF-8',
        },
      }),
      invalidatesTags: ['Token'],
    }),
    deleteToken: builder.mutation({
      query: ({ appId }) => ({
        url: `/api/v1/tokens/${appId}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Token'],
    }),
    getMirrors: builder.query<MirrorDto[], string>({
      query: (projectName) => `/api/v1/projects/${projectName}/mirrors`,
      providesTags: ['Metadata'],
    }),
    getMirror: builder.query<MirrorDto, { projectName: string; id: string }>({
      query: ({ projectName, id }) => `/api/v1/projects/${projectName}/mirrors/${id}`,
      providesTags: ['Metadata'],
    }),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    addNewMirror: builder.mutation<any, MirrorDto>({
      query: (mirror) => ({
        url: `/api/v1/projects/${mirror.projectName}/mirrors`,
        method: 'POST',
        body: mirror,
      }),
      invalidatesTags: ['Metadata'],
    }),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    updateMirror: builder.mutation<any, { projectName: string; id: string; mirror: MirrorDto }>({
      query: ({ projectName, id, mirror }) => ({
        url: `/api/v1/projects/${projectName}/mirrors/${id}`,
        method: 'PUT',
        body: mirror,
      }),
      invalidatesTags: ['Metadata'],
    }),
    deleteMirror: builder.mutation({
      query: ({ projectName, id }) => ({
        url: `/api/v1/projects/${projectName}/mirrors/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Metadata'],
    }),
    runMirror: builder.mutation<MirrorResult, { projectName: string; id: string }>({
      query: ({ projectName, id }) => ({
        url: `/api/v1/projects/${projectName}/mirrors/${id}/run`,
        method: 'POST',
      }),
      invalidatesTags: ['Metadata'],
    }),
    getCredentials: builder.query<CredentialDto[], string>({
      query: (projectName) => `/api/v1/projects/${projectName}/credentials`,
      providesTags: ['Metadata'],
    }),
    getCredential: builder.query<CredentialDto, { projectName: string; id: string }>({
      query: ({ projectName, id }) => `/api/v1/projects/${projectName}/credentials/${id}`,
      providesTags: ['Metadata'],
    }),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    addNewCredential: builder.mutation<any, { projectName: string; credential: CredentialDto }>({
      query: ({ projectName, credential }) => ({
        url: `/api/v1/projects/${projectName}/credentials`,
        method: 'POST',
        body: credential,
      }),
      invalidatesTags: ['Metadata'],
    }),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    updateCredential: builder.mutation<any, { projectName: string; id: string; credential: CredentialDto }>({
      query: ({ projectName, id, credential }) => ({
        url: `/api/v1/projects/${projectName}/credentials/${id}`,
        method: 'PUT',
        body: credential,
      }),
      invalidatesTags: ['Metadata'],
    }),
    deleteCredential: builder.mutation({
      query: ({ projectName, id }) => ({
        url: `/api/v1/projects/${projectName}/credentials/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Metadata'],
    }),
    getTitle: builder.query<TitleDto, void>({
      query: () => ({
        baseUrl: '',
        url: `/title`,
        method: 'GET',
      }),
    }),
  }),
});

export const {
  // Project
  useGetProjectsQuery,
  useRestoreProjectMutation,
  useAddNewProjectMutation,
  useDeleteProjectMutation,
  // Metadata
  useGetMetadataByProjectNameQuery,
  useAddNewMemberMutation,
  useDeleteMemberMutation,
  useAddNewTokenMemberMutation,
  useDeleteTokenMemberMutation,
  useUpdateRolePermissionMutation,
  useAddUserPermissionMutation,
  useDeleteUserPermissionMutation,
  useAddTokenPermissionMutation,
  useDeleteTokenPermissionMutation,
  // Repo
  useGetReposQuery,
  useAddNewRepoMutation,
  useDeleteRepoMutation,
  useRestoreRepoMutation,
  // Token
  useGetTokensQuery,
  useAddNewTokenMutation,
  useDeactivateTokenMutation,
  useActivateTokenMutation,
  useDeleteTokenMutation,
  // File
  useGetFilesQuery,
  useGetFileContentQuery,
  usePushFileChangesMutation,
  // History
  useGetHistoryQuery,
  useGetNormalisedRevisionQuery,
  // Mirror
  useGetMirrorsQuery,
  useGetMirrorQuery,
  useAddNewMirrorMutation,
  useUpdateMirrorMutation,
  useDeleteMirrorMutation,
  useRunMirrorMutation,
  // Credential
  useGetCredentialsQuery,
  useGetCredentialQuery,
  useAddNewCredentialMutation,
  useUpdateCredentialMutation,
  useDeleteCredentialMutation,
  // Title
  useGetTitleQuery,
} = apiSlice;
