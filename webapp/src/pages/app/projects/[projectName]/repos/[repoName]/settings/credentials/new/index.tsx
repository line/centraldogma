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

import Router, { useRouter } from 'next/router';
import { useAppDispatch } from 'dogma/hooks';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { SerializedError } from '@reduxjs/toolkit';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAddNewRepoCredentialMutation } from 'dogma/features/api/apiSlice';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import React from 'react';
import {
  CredentialDto,
  CreateCredentialRequestDto,
} from 'dogma/features/project/settings/credentials/CredentialDto';
import CredentialForm from 'dogma/features/project/settings/credentials/CredentialForm';

const EMPTY_CREDENTIAL: CredentialDto = {
  id: '',
  name: '',
  type: 'SSH_KEY',
};
const NewRepoCredentialPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';

  const [addNewCredential, { isLoading }] = useAddNewRepoCredentialMutation();
  const dispatch = useAppDispatch();

  const onSubmit = async (credential: CredentialDto, onSuccess: () => void) => {
    try {
      const credentialRequest: CreateCredentialRequestDto = {
        credentialId: credential.id,
        credential: credential,
      };
      const response = await addNewCredential({ projectName, credentialRequest, repoName }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification('New credential is created', `Successfully created`, 'success'));
      onSuccess();
      Router.push(`/app/projects/${projectName}/repos/${repoName}/settings/credentials`);
    } catch (error) {
      dispatch(newNotification(`Failed to create a new credential`, ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <>
      <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
      <CredentialForm
        projectName={projectName}
        repoName={repoName}
        defaultValue={EMPTY_CREDENTIAL}
        onSubmit={onSubmit}
        isWaitingResponse={isLoading}
      />
    </>
  );
};

export default NewRepoCredentialPage;
