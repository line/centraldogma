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

import { useRouter } from 'next/router';
import { Spacer } from '@chakra-ui/react';
import { useGetCredentialsQuery, useGetMirrorQuery } from 'dogma/features/api/apiSlice';
import { Deferred } from 'dogma/common/components/Deferred';
import React from 'react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import MirrorView from 'dogma/features/mirror/MirrorView';
import { CredentialDto } from 'dogma/features/credential/CredentialDto';

const MirrorViewPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const index = parseInt(router.query.index as string, 10);
  const {
    data: mirror,
    isLoading: isMirrorLoading,
    error: mirrorError,
  } = useGetMirrorQuery({ projectName, index });
  const {
    data: credentials,
    isLoading: isCredentialLoading,
    error: credentialError,
  } = useGetCredentialsQuery(projectName);
  const credential = (credentials || []).find((credential: CredentialDto) => {
    return credential.id === mirror?.credentialId;
  });

  return (
    <Deferred isLoading={isMirrorLoading || isCredentialLoading} error={mirrorError || credentialError}>
      {() => {
        return (
          <>
            <Breadcrumbs path={router.asPath} omitIndexList={[0]} replaces={{ 4: mirror.id }} />
            <Spacer />
            <MirrorView projectName={projectName} mirror={mirror} credential={credential} />
          </>
        );
      }}
    </Deferred>
  );
};

export default MirrorViewPage;
