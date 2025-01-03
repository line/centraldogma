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
import { useAddNewMirrorAccessControlMutation } from 'dogma/features/api/apiSlice';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import React from 'react';
import MirrorAccessControlForm from 'dogma/features/settings/mirror-access/MirrorAccessControlForm';
import { MirrorAccessControlRequest } from 'dogma/features/settings/mirror-access/MirrorAccessControl';

const EMPTY_MACR: MirrorAccessControlRequest = {
  id: '',
  targetPattern: '',
  allow: null,
  description: '',
  order: 0,
};
const NewMirrorAccessControlPage = () => {
  const router = useRouter();

  const [addNewMirrorAccessControl, { isLoading }] = useAddNewMirrorAccessControlMutation();
  const dispatch = useAppDispatch();

  const onSubmit = async (data: MirrorAccessControlRequest, onSuccess: () => void) => {
    try {
      const response = await addNewMirrorAccessControl(data).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification('New mirror access control is created', `Successfully created`, 'success'));
      onSuccess();
      Router.push(`/app/settings/mirror-access`);
    } catch (error) {
      dispatch(
        newNotification(
          `Failed to create a new mirror access control`,
          ErrorMessageParser.parse(error),
          'error',
        ),
      );
    }
  };

  return (
    <>
      <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
      <MirrorAccessControlForm defaultValue={EMPTY_MACR} onSubmit={onSubmit} isWaitingResponse={isLoading} />
    </>
  );
};

export default NewMirrorAccessControlPage;
