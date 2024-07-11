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

import { Deferred } from 'dogma/common/components/Deferred';
import Router, { useRouter } from 'next/router';
import { useGetMirrorQuery, useUpdateMirrorMutation } from 'dogma/features/api/apiSlice';
import { useAppDispatch } from 'dogma/hooks';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { SerializedError } from '@reduxjs/toolkit';
import { createMessage } from 'dogma/features/message/messageSlice';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import React from 'react';
import { MirrorDto } from 'dogma/features/project/settings/mirrors/MirrorDto';
import MirrorForm from 'dogma/features/project/settings/mirrors/MirrorForm';

const MirrorEditPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const id = router.query.id as string;

  const { data, isLoading: isMirrorLoading, error } = useGetMirrorQuery({ projectName, id });
  const [updateMirror, { isLoading: isWaitingMutationResponse }] = useUpdateMirrorMutation();
  const dispatch = useAppDispatch();

  const onSubmit = async (mirror: MirrorDto, onSuccess: () => void) => {
    try {
      mirror.projectName = projectName;
      const response = await updateMirror({ projectName, id, mirror }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(
        createMessage({
          title: `Mirror '${mirror.id}' is updated`,
          text: `Successfully updated`,
          type: 'success',
        }),
      );
      onSuccess();
      Router.push(`/app/projects/${projectName}/settings/mirrors/${id}`);
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to update the mirror`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
    }
  };
  return (
    <Deferred isLoading={isMirrorLoading} error={error}>
      {() => (
        <>
          <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
          <MirrorForm
            projectName={projectName}
            defaultValue={data}
            onSubmit={onSubmit}
            isWaitingResponse={isWaitingMutationResponse}
          />
        </>
      )}
    </Deferred>
  );
};

export default MirrorEditPage;
