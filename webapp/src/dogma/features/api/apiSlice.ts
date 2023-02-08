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
    prepareHeaders: (headers, { getState }) => {
      const { auth } = getState() as { auth: AuthState };
      headers.set('Authorization', `Bearer ${auth?.sessionId}`);
      return headers;
    },
  }),
  tagTypes: ['Project', 'Repo', 'File', 'Token'],
  endpoints: (builder) => ({
    getProjects: builder.query<ProjectDto[], void>({
      query: () => '/v1/projects',
      providesTags: ['Project'],
    }),
    addNewProject: builder.mutation({
      query: (payload) => ({
        url: `/v1/projects`,
        method: 'POST',
        body: payload,
        headers: {
          'Content-type': 'application/json; charset=UTF-8',
        },
      }),
      invalidatesTags: ['Project'],
    }),
    getMetadataByProjectName: builder.query<ProjectMetadataDto, string>({
      query: (projectName) => `/v1/projects/${projectName}`,
    }),
    getReposByProjectName: builder.query<RepoDto[], string>({
      query: (projectName) => `/v1/projects/${projectName}/repos`,
      providesTags: ['Repo'],
    }),
    addNewRepo: builder.mutation({
      query: ({ projectName, data }) => ({
        url: `/v1/projects/${projectName}/repos`,
        method: 'POST',
        body: data,
        headers: {
          'Content-type': 'application/json; charset=UTF-8',
        },
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
        headers: {
          'Content-type': 'application/json; charset=UTF-8',
        },
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
        url: `/v1/tokens?${data}`,
        method: 'POST',
        headers: {
          'Content-type': 'application/json; charset=UTF-8',
        },
      }),
      invalidatesTags: ['Token'],
    }),
    deactivateToken: builder.mutation({
      query: ({ appId }) => ({
        url: `/v1/tokens/${appId}`,
        method: 'PATCH',
        body: [{ op: 'replace', path: '/status', value: 'inactive' }],
        headers: {
          'Content-type': 'application/json-patch+json; charset=UTF-8',
        },
      }),
      invalidatesTags: ['Token'],
    }),
    ActivateToken: builder.mutation({
      query: ({ appId }) => ({
        url: `/v1/tokens/${appId}`,
        method: 'PATCH',
        body: [{ op: 'replace', path: '/status', value: 'active' }],
        headers: {
          'Content-type': 'application/json-patch+json; charset=UTF-8',
        },
      }),
      invalidatesTags: ['Token'],
    }),
    DeleteToken: builder.mutation({
      query: ({ appId }) => ({
        url: `/v1/tokens/${appId}`,
        method: 'DELETE',
        headers: {
          'Content-type': 'application/json; charset=UTF-8',
        },
      }),
      invalidatesTags: ['Token'],
    }),
  }),
});

export const {
  useAddNewProjectMutation,
  useAddNewRepoMutation,
  usePushFileChangesMutation,
  useAddNewTokenMutation,
  useDeactivateTokenMutation,
  useActivateTokenMutation,
  useDeleteTokenMutation,
  useGetProjectsQuery,
  useGetMetadataByProjectNameQuery,
  useGetReposByProjectNameQuery,
  useGetFilesQuery,
  useGetFileContentQuery,
  useGetHistoryQuery,
  useGetNormalisedRevisionQuery,
  useGetTokensQuery,
} = apiSlice;
