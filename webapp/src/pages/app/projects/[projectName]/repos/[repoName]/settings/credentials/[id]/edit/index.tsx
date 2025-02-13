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
import { useGetRepoCredentialQuery, useUpdateRepoCredentialMutation } from 'dogma/features/api/apiSlice';
import { useAppDispatch } from 'dogma/hooks';
import { Deferred } from 'dogma/common/components/Deferred';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { SerializedError } from '@reduxjs/toolkit';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import React from 'react';
import { CredentialDto } from 'dogma/features/project/settings/credentials/CredentialDto';
import CredentialForm from 'dogma/features/project/settings/credentials/CredentialForm';

const RepoCredentialEditPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const repoName = router.query.repoName as string;
  const id = router.query.id as string;

  const {
    data,
    isLoading: isCredentialLoading,
    error,
  } = useGetRepoCredentialQuery({ projectName, id, repoName });
  const [updateCredential, { isLoading: isWaitingMutationResponse }] = useUpdateRepoCredentialMutation();
  const dispatch = useAppDispatch();

  const onSubmit = async (credential: CredentialDto, onSuccess: () => void) => {
    try {
      credential.name = `projects/${projectName}/repos/${repoName}/credentials/${credential.id}`;
      const response = await updateCredential({ projectName, id, credential, repoName }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification(`Credential '${credential.id}' is updated`, `Successfully updated`, 'success'));
      onSuccess();
      Router.push(`/app/projects/${projectName}/repos/${repoName}/settings/credentials/${id}`);
    } catch (error) {
      dispatch(newNotification(`Failed to update the credential`, ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <Deferred isLoading={isCredentialLoading} error={error}>
      {() => {
        return (
          <>
            <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
            <CredentialForm
              projectName={projectName}
              repoName={repoName}
              defaultValue={data}
              onSubmit={onSubmit}
              isWaitingResponse={isWaitingMutationResponse}
            />
          </>
        );
      }}
    </Deferred>
  );
};

export default RepoCredentialEditPage;
