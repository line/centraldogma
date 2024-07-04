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
import { DeleteMemberDto } from 'dogma/features/metadata/DeleteMemberDto';
import { FetchBaseQueryError } from '@reduxjs/toolkit/dist/query';
import { DeleteUserPermissionDto } from 'dogma/features/repo/permissions/DeleteUserPermissionDto';
import { AddUserPermissionDto } from 'dogma/features/repo/permissions/AddUserPermissionDto';
import { MirrorDto } from 'dogma/features/mirror/MirrorDto';
import { CredentialDto } from 'dogma/features/credential/CredentialDto';

export type GetHistory = {
  projectName: string;
  repoName: string;
  revision: number;
  to: number;
};

export type GetNormalisedRevision = {
  projectName: string;
  repoName: string;
  revision: number;
};

export type GetFilesByProjectAndRepoName = {
  projectName: string;
  repoName: string;
  revision?: string;
  filePath?: string;
};

export type GetFileContent = {
  projectName: string;
  repoName: string;
  filePath: string;
};

export const apiSlice = createApi({
  reducerPath: 'api',
  baseQuery: fetchBaseQuery({
    baseUrl: `${process.env.NEXT_PUBLIC_HOST || ''}/api`,
    prepareHeaders: (headers, { getState, type }) => {
      const { auth } = getState() as { auth: AuthState };
      headers.set('Authorization', `Bearer ${auth?.sessionId || 'anonymous'}`);
      if (type === 'mutation') {
        headers.set('Content-type', 'application/json; charset=UTF-8');
      }
      return headers;
    },
  }),
  tagTypes: ['Project', 'Metadata', 'Repo', 'File', 'Token'],
  endpoints: (builder) => ({
    getProjects: builder.query<ProjectDto[], { admin: boolean }>({
      async queryFn(arg, _queryApi, _extraOptions, fetchWithBQ) {
        const projects = await fetchWithBQ('/v1/projects');
        if (projects.error) return { error: projects.error as FetchBaseQueryError };
        if (arg.admin) {
          const removedProjects = await fetchWithBQ('/v1/projects?status=removed');
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
        url: `/v1/projects`,
        method: 'POST',
        body: payload,
      }),
      invalidatesTags: ['Project'],
    }),
    deleteProject: builder.mutation({
      query: ({ projectName }) => ({
        url: `/v1/projects/${projectName}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Project'],
    }),
    restoreProject: builder.mutation({
      query: ({ projectName }) => ({
        url: `/v1/projects/${projectName}`,
        method: 'PATCH',
        body: [{ op: 'replace', path: '/status', value: 'active' }],
      }),
      invalidatesTags: ['Project'],
    }),
    getMetadataByProjectName: builder.query<ProjectMetadataDto, string>({
      query: (projectName) => `/v1/projects/${projectName}`,
      providesTags: ['Repo', 'Metadata'],
    }),
    addNewMember: builder.mutation({
      query: ({ projectName, id, role }) => ({
        url: `/v1/metadata/${projectName}/members`,
        method: 'POST',
        body: { id, role: role.toUpperCase() },
      }),
      invalidatesTags: ['Metadata'],
    }),
    deleteMember: builder.mutation<void, DeleteMemberDto>({
      query: ({ projectName, id }) => ({
        url: `/v1/metadata/${projectName}/members/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Metadata'],
    }),
    addNewTokenMember: builder.mutation({
      query: ({ projectName, id, role }) => ({
        url: `/v1/metadata/${projectName}/tokens`,
        method: 'POST',
        body: { id, role: role.toUpperCase() },
      }),
      invalidatesTags: ['Metadata'],
    }),
    deleteTokenMember: builder.mutation<void, DeleteMemberDto>({
      query: ({ projectName, id }) => ({
        url: `/v1/metadata/${projectName}/tokens/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Metadata'],
    }),
    updateRolePermission: builder.mutation({
      query: ({ projectName, repoName, data }) => ({
        url: `/v1/metadata/${projectName}/repos/${repoName}/perm/role`,
        method: 'POST',
        body: data,
      }),
      invalidatesTags: ['Metadata'],
    }),
    addUserPermission: builder.mutation<void, AddUserPermissionDto>({
      query: ({ projectName, repoName, data }) => ({
        url: `/v1/metadata/${projectName}/repos/${repoName}/perm/users`,
        method: 'POST',
        body: data,
      }),
      invalidatesTags: ['Metadata'],
    }),
    deleteUserPermission: builder.mutation<void, DeleteUserPermissionDto>({
      query: ({ projectName, repoName, id }) => ({
        url: `/v1/metadata/${projectName}/repos/${repoName}/perm/users/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Metadata'],
    }),
    addTokenPermission: builder.mutation<void, AddUserPermissionDto>({
      query: ({ projectName, repoName, data }) => ({
        url: `/v1/metadata/${projectName}/repos/${repoName}/perm/tokens`,
        method: 'POST',
        body: data,
      }),
      invalidatesTags: ['Metadata'],
    }),
    deleteTokenPermission: builder.mutation<void, DeleteUserPermissionDto>({
      query: ({ projectName, repoName, id }) => ({
        url: `/v1/metadata/${projectName}/repos/${repoName}/perm/tokens/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Metadata'],
    }),
    getRepos: builder.query<RepoDto[], string>({
      query: (projectName) => `/v1/projects/${projectName}/repos`,
      providesTags: ['Repo'],
    }),
    addNewRepo: builder.mutation({
      query: ({ projectName, data }) => ({
        url: `/v1/projects/${projectName}/repos`,
        method: 'POST',
        body: data,
      }),
      invalidatesTags: ['Repo'],
    }),
    deleteRepo: builder.mutation({
      query: ({ projectName, repoName }) => ({
        url: `/v1/projects/${projectName}/repos/${repoName}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Repo'],
    }),
    restoreRepo: builder.mutation({
      query: ({ projectName, repoName }) => ({
        url: `/v1/projects/${projectName}/repos/${repoName}`,
        method: 'PATCH',
        body: [{ op: 'replace', path: '/status', value: 'active' }],
      }),
      invalidatesTags: ['Repo'],
    }),
    getFiles: builder.query<FileDto[], GetFilesByProjectAndRepoName>({
      query: ({ projectName, repoName, revision, filePath }) =>
        `/v1/projects/${projectName}/repos/${repoName}/list${filePath || ''}?revision=${revision || 'head'}`,
      providesTags: ['File'],
    }),
    getFileContent: builder.query<FileContentDto, GetFileContent>({
      query: ({ projectName, repoName, filePath }) =>
        `/v1/projects/${projectName}/repos/${repoName}/contents/${filePath}`,
      providesTags: ['File'],
    }),
    pushFileChanges: builder.mutation({
      query: ({ projectName, repoName, data }) => ({
        url: `/v1/projects/${projectName}/repos/${repoName}/contents`,
        method: 'POST',
        body: data,
      }),
      invalidatesTags: ['File'],
    }),
    getHistory: builder.query<HistoryDto[], GetHistory>({
      query: ({ projectName, repoName, revision, to }) =>
        `/v1/projects/${projectName}/repos/${repoName}/commits/${revision}?to=${to}`,
      providesTags: ['File'],
    }),
    getNormalisedRevision: builder.query<RevisionDto, GetNormalisedRevision>({
      query: ({ projectName, repoName, revision }) =>
        `/v1/projects/${projectName}/repos/${repoName}/revision/${revision}`,
      providesTags: ['File'],
    }),
    getTokens: builder.query<TokenDto[], void>({
      query: () => '/v1/tokens',
      providesTags: ['Token'],
    }),
    addNewToken: builder.mutation({
      query: ({ data }) => ({
        url: `/v1/tokens`,
        method: 'POST',
        body: data,
      }),
      invalidatesTags: ['Token'],
    }),
    deactivateToken: builder.mutation({
      query: ({ appId }) => ({
        url: `/v1/tokens/${appId}`,
        method: 'PATCH',
        body: [{ op: 'replace', path: '/status', value: 'inactive' }],
      }),
      invalidatesTags: ['Token'],
    }),
    activateToken: builder.mutation({
      query: ({ appId }) => ({
        url: `/v1/tokens/${appId}`,
        method: 'PATCH',
        body: [{ op: 'replace', path: '/status', value: 'active' }],
      }),
      invalidatesTags: ['Token'],
    }),
    deleteToken: builder.mutation({
      query: ({ appId }) => ({
        url: `/v1/tokens/${appId}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Token'],
    }),
    getMirrors: builder.query<MirrorDto[], string>({
      query: (projectName) => `/v1/projects/${projectName}/mirrors`,
      providesTags: ['Metadata'],
    }),
    getMirror: builder.query<MirrorDto, { projectName: string; id: string }>({
      query: ({ projectName, id }) => `/v1/projects/${projectName}/mirrors/${id}`,
      providesTags: ['Metadata'],
    }),
    addNewMirror: builder.mutation<any, MirrorDto>({
      query: (mirror) => ({
        url: `/v1/projects/${mirror.projectName}/mirrors`,
        method: 'POST',
        body: mirror,
      }),
      invalidatesTags: ['Metadata'],
    }),
    updateMirror: builder.mutation<any, { projectName: string; id: string; mirror: MirrorDto }>({
      query: ({ projectName, id, mirror }) => ({
        url: `/v1/projects/${projectName}/mirrors/${id}`,
        method: 'PUT',
        body: mirror,
      }),
      invalidatesTags: ['Metadata'],
    }),
    getCredentials: builder.query<CredentialDto[], string>({
      query: (projectName) => `/v1/projects/${projectName}/credentials`,
      providesTags: ['Metadata'],
    }),
    getCredential: builder.query<CredentialDto, { projectName: string; id: string }>({
      query: ({ projectName, id }) => `/v1/projects/${projectName}/credentials/${id}`,
      providesTags: ['Metadata'],
    }),
    addNewCredential: builder.mutation<any, { projectName: string; credential: CredentialDto }>({
      query: ({ projectName, credential }) => ({
        url: `/v1/projects/${projectName}/credentials`,
        method: 'POST',
        body: credential,
      }),
      invalidatesTags: ['Metadata'],
    }),
    updateCredential: builder.mutation<any, { projectName: string; id: string; credential: CredentialDto }>({
      query: ({ projectName, id, credential }) => ({
        url: `/v1/projects/${projectName}/credentials/${id}`,
        method: 'PUT',
        body: credential,
      }),
      invalidatesTags: ['Metadata'],
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
  // Credential
  useGetCredentialsQuery,
  useGetCredentialQuery,
  useAddNewCredentialMutation,
  useUpdateCredentialMutation,
} = apiSlice;
