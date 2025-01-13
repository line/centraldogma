/*
 * Copyright 2024 LINE Corporation
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
import {
  useGetMirrorAccessControlQuery,
  useUpdateMirrorAccessControlMutation,
} from 'dogma/features/api/apiSlice';
import { useAppDispatch } from 'dogma/hooks';
import { Deferred } from 'dogma/common/components/Deferred';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { SerializedError } from '@reduxjs/toolkit';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import React from 'react';
import MirrorAccessControlForm from 'dogma/features/settings/mirror-access/MirrorAccessControlForm';
import { MirrorAccessControlRequest } from 'dogma/features/settings/mirror-access/MirrorAccessControl';

const MirrorAccessControlEditPage = () => {
  const router = useRouter();
  const id = router.query.id as string;

  const { data, isLoading: isDataLoading, error } = useGetMirrorAccessControlQuery({ id });
  const [updateMirrorAccessControl, { isLoading: isWaitingMutationResponse }] =
    useUpdateMirrorAccessControlMutation();
  const dispatch = useAppDispatch();

  const onSubmit = async (data: MirrorAccessControlRequest, onSuccess: () => void) => {
    try {
      const response = await updateMirrorAccessControl(data).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(
        newNotification(`Mirror access control '${data.id}' is updated`, `Successfully updated`, 'success'),
      );
      onSuccess();
      Router.push(`/app/settings/mirror-access/${id}`);
    } catch (error) {
      dispatch(
        newNotification(`Failed to update the mirror access control`, ErrorMessageParser.parse(error), 'error'),
      );
    }
  };

  return (
    <Deferred isLoading={isDataLoading} error={error}>
      {() => (
        <>
          <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
          <MirrorAccessControlForm
            defaultValue={data}
            onSubmit={onSubmit}
            isWaitingResponse={isWaitingMutationResponse}
          />
        </>
      )}
    </Deferred>
  );
};

export default MirrorAccessControlEditPage;
