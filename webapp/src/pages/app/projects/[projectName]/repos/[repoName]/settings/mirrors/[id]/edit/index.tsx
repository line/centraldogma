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
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import React from 'react';
import { MirrorDto, MirrorRequest } from 'dogma/features/repo/settings/mirrors/MirrorRequest';
import MirrorForm from 'dogma/features/repo/settings/mirrors/MirrorForm';

const MirrorEditPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const repoName = router.query.repoName as string;
  const id = router.query.id as string;

  const { data, isLoading: isMirrorLoading, error } = useGetMirrorQuery({ projectName, repoName, id });
  const [updateMirror, { isLoading: isWaitingMutationResponse }] = useUpdateMirrorMutation();
  const dispatch = useAppDispatch();

  const onSubmit = async (mirror: MirrorRequest | MirrorDto, onSuccess: () => void) => {
    try {
      let mirrorRequest: MirrorRequest;

      if ('allow' in mirror) {
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        const { allow, ...rest } = mirror;
        mirrorRequest = rest;
      } else {
        mirrorRequest = mirror;
      }

      mirrorRequest.projectName = projectName;
      mirrorRequest.localRepo = repoName;

      mirrorRequest.projectName = projectName;
      mirrorRequest.localRepo = repoName;

      const response = await updateMirror({ projectName, repoName, id, mirror: mirrorRequest }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification(`Mirror '${mirror.id}' is updated`, `Successfully updated`, 'success'));
      onSuccess();
      Router.push(`/app/projects/${projectName}/repos/${repoName}/settings/mirrors/${id}`);
    } catch (error) {
      dispatch(newNotification(`Failed to update the mirror`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <Deferred isLoading={isMirrorLoading} error={error}>
      {() => (
        <>
          <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
          <MirrorForm
            projectName={projectName}
            repoName={repoName}
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
