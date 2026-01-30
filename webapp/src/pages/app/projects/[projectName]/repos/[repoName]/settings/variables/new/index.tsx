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
import { useAppDispatch } from 'dogma/hooks';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { SerializedError } from '@reduxjs/toolkit';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAddNewVariableMutation } from 'dogma/features/api/apiSlice';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import React from 'react';
import { VariableDto } from 'dogma/features/project/settings/variables/VariableDto';
import VariableForm from 'dogma/features/project/settings/variables/VariableForm';

const EMPTY_VARIABLE: VariableDto = {
  id: '',
  name: '',
  type: 'STRING',
  value: '',
};
const NewRepoVariablePage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';

  const [addNewVariable, { isLoading }] = useAddNewVariableMutation();
  const dispatch = useAppDispatch();

  const onSubmit = async (newVariable: VariableDto, onSuccess: () => void) => {
    try {
      const response = await addNewVariable({ projectName, repoName, newVariable }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification('New variable is created', `Successfully created`, 'success'));
      onSuccess();
      Router.push(`/app/projects/${projectName}/repos/${repoName}/settings/variables`);
    } catch (error) {
      dispatch(newNotification(`Failed to create a new variable`, ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <>
      <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
      <VariableForm
        projectName={projectName}
        repoName={repoName}
        defaultValue={EMPTY_VARIABLE}
        onSubmit={onSubmit}
        isWaitingResponse={isLoading}
      />
    </>
  );
};

export default NewRepoVariablePage;
