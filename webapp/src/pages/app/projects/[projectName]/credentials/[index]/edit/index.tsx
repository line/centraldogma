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
import { useGetCredentialQuery, useUpdateCredentialMutation } from 'dogma/features/api/apiSlice';
import { useAppDispatch } from 'dogma/store';
import { Deferred } from 'dogma/common/components/Deferred';
import CredentialForm from 'dogma/features/credential/CredentialForm';
import { CredentialDto } from 'dogma/features/credential/CredentialDto';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { SerializedError } from '@reduxjs/toolkit';
import { createMessage } from 'dogma/features/message/messageSlice';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import React from 'react';

const CredentialEditPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const index = parseInt(router.query.index as string, 10);

  const { data, isLoading: isCredentialLoading, error } = useGetCredentialQuery({ projectName, index });
  const [updateCredential, { isLoading: isWaitingMutationResponse }] = useUpdateCredentialMutation();
  const dispatch = useAppDispatch();

  const onSubmit = async (credential: CredentialDto, onSuccess: () => void) => {
    try {
      const response = await updateCredential({ projectName, index, credential }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(
        createMessage({
          title: `Credential '${credential.id}' is updated`,
          text: `Successfully updated`,
          type: 'success',
        }),
      );
      onSuccess();
      Router.push(`/app/projects/${projectName}/credentials/${index}`);
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to update the credential`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
    }
  };

  return (
    <Deferred isLoading={isCredentialLoading} error={error}>
      {() => {
        return (
          <>
            <Breadcrumbs path={router.asPath} omitIndexList={[0]} replaces={{ 4: data.id }} />
            <CredentialForm
              projectName={projectName}
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

export default CredentialEditPage;
