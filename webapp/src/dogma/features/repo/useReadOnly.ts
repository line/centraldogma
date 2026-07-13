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

import { useGetProjectsQuery, useGetReposQuery } from 'dogma/features/api/apiSlice';
import { isInternalProject } from 'dogma/util/repo-util';

export const PROJECT_READ_ONLY_HINT = 'This project is read-only.';
export const REPO_READ_ONLY_HINT = 'This repository is read-only.';

/**
 * Returns whether the project is read-only. The status is unknown while the projects are being fetched, in
 * which case {@code false} is returned so that a write action is never disabled by a transient loading error.
 */
export const useProjectReadOnly = (projectName: string): boolean => {
  // An internal project is not writable by the web UI anyway.
  const skip = !projectName || isInternalProject(projectName);
  const { data: projects } = useGetProjectsQuery({ systemAdmin: false }, { skip });
  if (skip) {
    return false;
  }
  return projects?.find((project) => project.name === projectName)?.status === 'READ_ONLY';
};

/**
 * Returns whether the repository is read-only. A repository is read-only when the server, the project or the
 * repository itself is in read-only mode, because the server returns the effective status of the repository.
 */
export const useRepoReadOnly = (projectName: string, repoName: string): boolean => {
  // The repositories of an internal project are only visible to a system administrator.
  const skip = !projectName || !repoName || isInternalProject(projectName);
  const { data: repos } = useGetReposQuery(projectName, { skip });
  if (skip) {
    return false;
  }
  return repos?.find((repo) => repo.name === repoName)?.status === 'READ_ONLY';
};

/**
 * Returns whether the repository is read-only, along with the reason to show when it is. The project is
 * checked first so that a project-scoped read-only is not reported as a repository-scoped one.
 */
export const useReadOnly = (projectName: string, repoName: string): [boolean, string] => {
  const projectReadOnly = useProjectReadOnly(projectName);
  const repoReadOnly = useRepoReadOnly(projectName, repoName);
  return [projectReadOnly || repoReadOnly, projectReadOnly ? PROJECT_READ_ONLY_HINT : REPO_READ_ONLY_HINT];
};
