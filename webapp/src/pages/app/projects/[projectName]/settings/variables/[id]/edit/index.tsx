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

import Router, { useRouter } from 'next/router';
import { useGetVariableQuery, useUpdateVariableMutation } from 'dogma/features/api/apiSlice';
import { useAppDispatch } from 'dogma/hooks';
import { Deferred } from 'dogma/common/components/Deferred';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { SerializedError } from '@reduxjs/toolkit';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import React from 'react';
import { VariableDto } from 'dogma/features/project/settings/variables/VariableDto';
import VariableForm from 'dogma/features/project/settings/variables/VariableForm';

const VariableEditPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const id = router.query.id as string;

  const { data, isLoading: isVariableLoading, error } = useGetVariableQuery({ projectName, id });
  const [updateVariable, { isLoading: isWaitingMutationResponse }] = useUpdateVariableMutation();
  const dispatch = useAppDispatch();

  const onSubmit = async (variable: VariableDto, onSuccess: () => void) => {
    try {
      variable.name = `projects/${projectName}/variables/${variable.id}`;
      const response = await updateVariable({ projectName, id, variable }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification(`Variable '${variable.id}' is updated`, `Successfully updated`, 'success'));
      onSuccess();
      Router.push(`/app/projects/${projectName}/settings/variables/${id}`);
    } catch (error) {
      dispatch(newNotification(`Failed to update the variable`, ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <Deferred isLoading={isVariableLoading} error={error}>
      {() => {
        return (
          <>
            <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
            <VariableForm
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

export default VariableEditPage;
