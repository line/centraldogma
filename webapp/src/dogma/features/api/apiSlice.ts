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

export type GetHistory = {
  projectName: string;
  repoName: string;
  revision: number;
  size: number;
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
};

export type GetFileContent = {
  projectName: string;
  repoName: string;
  revision: string;
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
  tagTypes: ['Project', 'Repo'],
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
    getFilesByProjectAndRepoName: builder.query<FileDto[], GetFilesByProjectAndRepoName>({
      query: ({ projectName, repoName }) => `/v1/projects/${projectName}/repos/${repoName}/list`,
    }),
    getFilesByProjectAndRepoAndRevisionName: builder.query<FileDto[], GetFilesByProjectAndRepoName>({
      query: ({ projectName, repoName, revision }) =>
        `/v1/projects/${projectName}/repos/${repoName}/list/?revision=${revision}`,
    }),
    getFileContent: builder.query<FileContentDto, GetFileContent>({
      query: ({ projectName, repoName, revision, filePath }) =>
        `/v1/projects/${projectName}/repos/${repoName}/files/revisions/${revision}/${filePath}?queryType=IDENTITY`,
    }),
    getHistory: builder.query<HistoryDto[], GetHistory>({
      query: ({ projectName, repoName, revision, size }) =>
        `/v1/projects/${projectName}/repos/${repoName}/commits/${revision}?to=${size}`,
    }),
    getNormalisedRevision: builder.query<RevisionDto, GetNormalisedRevision>({
      query: ({ projectName, repoName, revision }) =>
        `/v1/projects/${projectName}/repos/${repoName}/revision/${revision}`,
    }),
  }),
});

export const {
  useAddNewProjectMutation,
  useAddNewRepoMutation,
  useGetProjectsQuery,
  useGetMetadataByProjectNameQuery,
  useGetReposByProjectNameQuery,
  useGetFilesByProjectAndRepoNameQuery,
  useGetFilesByProjectAndRepoAndRevisionNameQuery,
  useGetFileContentQuery,
  useGetHistoryQuery,
  useGetNormalisedRevisionQuery,
} = apiSlice;
