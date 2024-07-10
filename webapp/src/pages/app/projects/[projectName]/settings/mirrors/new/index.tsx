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
import { createMessage } from 'dogma/features/message/messageSlice';
import Router, { useRouter } from 'next/router';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import React from 'react';
import { MirrorDto } from 'dogma/features/project/settings/mirrors/MirrorDto';
import MirrorForm from 'dogma/features/project/settings/mirrors/MirrorForm';

const NewMirrorPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';

  const emptyMirror: MirrorDto = {
    id: '',
    direction: null,
    schedule: '0 * * * * ?',
    projectName: projectName,
    localRepo: '',
    localPath: '',
    remoteScheme: '',
    remoteUrl: '',
    remoteBranch: '',
    remotePath: '',
    credentialId: null,
    gitignore: null,
    enabled: false,
  };

  const [addNewMirror, { isLoading }] = useAddNewMirrorMutation();
  const dispatch = useAppDispatch();

  const onSubmit = async (formData: MirrorDto, onSuccess: () => void, setError: UseFormSetError<MirrorDto>) => {
    try {
      formData.projectName = projectName;
      if (formData.remoteScheme.startsWith('git') && !formData.remoteUrl.endsWith('.git')) {
        setError('remoteUrl', { type: 'manual', message: "The remote path must end with '.git'" });
        return;
      }

      const response = await addNewMirror(formData).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(
        createMessage({
          title: 'New mirror is created',
          text: `Successfully created`,
          type: 'success',
        }),
      );
      onSuccess();
      Router.push(`/app/projects/${projectName}/settings/mirrors`);
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to create a new mirror`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
    }
  };

  return (
    <>
      <Breadcrumbs path={router.asPath} omitIndexList={[0]} />

      <MirrorForm
        projectName={projectName}
        defaultValue={emptyMirror}
        onSubmit={onSubmit}
        isWaitingResponse={isLoading}
      />
    </>
  );
};

export default NewMirrorPage;
