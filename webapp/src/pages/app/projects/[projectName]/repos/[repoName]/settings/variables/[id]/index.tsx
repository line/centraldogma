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

import { useRouter } from 'next/router';
import { useGetVariableQuery } from 'dogma/features/api/apiSlice';
import { Deferred } from 'dogma/common/components/Deferred';
import React from 'react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { Flex, Spacer } from '@chakra-ui/react';
import VariableView from 'dogma/features/project/settings/variables/VariableView';

const RepoVariableViewPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const repoName = router.query.repoName as string;
  const id = router.query.id as string;
  const { data, isLoading, error } = useGetVariableQuery({ projectName, repoName, id });
  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => {
        return (
          <>
            <Flex>
              <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
              <Spacer />
            </Flex>
            <VariableView projectName={projectName} repoName={repoName} variable={data} />
          </>
        );
      }}
    </Deferred>
  );
};

export default RepoVariableViewPage;
