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

import { UseFormSetError } from 'react-hook-form';
import { useAddNewMirrorMutation } from 'dogma/features/api/apiSlice';
import { useAppDispatch } from 'dogma/hooks';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { SerializedError } from '@reduxjs/toolkit';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import Router, { useRouter } from 'next/router';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import React from 'react';
import { MirrorRequest } from 'dogma/features/repo/settings/mirrors/MirrorRequest';
import MirrorForm from 'dogma/features/repo/settings/mirrors/MirrorForm';

const NewMirrorPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';

  const emptyMirror: MirrorRequest = {
    id: '',
    direction: 'REMOTE_TO_LOCAL',
    schedule: '0 * * * * ?',
    projectName: projectName,
    localRepo: '',
    localPath: '/',
    remoteScheme: '',
    remoteUrl: '',
    remoteBranch: 'main',
    remotePath: '/',
    credentialId: null,
    gitignore: null,
    enabled: false,
  };

  const [addNewMirror, { isLoading }] = useAddNewMirrorMutation();
  const dispatch = useAppDispatch();

  const onSubmit = async (
    formData: MirrorRequest,
    onSuccess: () => void,
    setError: UseFormSetError<MirrorRequest>,
  ) => {
    try {
      formData.projectName = projectName;
      formData.localRepo = repoName;
      if (formData.remoteScheme.startsWith('git') && !formData.remoteUrl.endsWith('.git')) {
        setError('remoteUrl', { type: 'manual', message: "The remote path must end with '.git'" });
        return;
      }

      const response = await addNewMirror(formData).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification('New mirror is created', `Successfully created`, 'success'));
      onSuccess();
      Router.push(`/app/projects/${projectName}/repos/${repoName}/settings/mirrors`);
    } catch (error) {
      dispatch(newNotification(`Failed to create a new mirror`, ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <>
      <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
      <MirrorForm
        projectName={projectName}
        repoName={repoName}
        defaultValue={emptyMirror}
        onSubmit={onSubmit}
        isWaitingResponse={isLoading}
      />
    </>
  );
};

export default NewMirrorPage;
