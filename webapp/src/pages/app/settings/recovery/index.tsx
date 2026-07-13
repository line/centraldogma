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

import { Alert, AlertIcon, Box } from '@chakra-ui/react';
import SettingView from 'dogma/features/settings/SettingView';
import { Deferred } from 'dogma/common/components/Deferred';
import { useGetReplicasQuery } from 'dogma/features/api/apiSlice';
import RecoverRepositoryForm from 'dogma/features/settings/recovery/RecoverRepositoryForm';

const RepositoryRecoveryPage = () => {
  const { data: replicas, error, isLoading } = useGetReplicasQuery();

  return (
    <SettingView currentTab="Repository Recovery">
      <Deferred isLoading={isLoading} error={error}>
        {() => (
          <Box p="4">
            {replicas && replicas.length > 0 ? (
              <RecoverRepositoryForm />
            ) : (
              <Alert status="info" borderRadius="md">
                <AlertIcon />
                Repository recovery is only available when the server runs in replicated (ZooKeeper) mode.
              </Alert>
            )}
          </Box>
        )}
      </Deferred>
    </SettingView>
  );
};

export default RepositoryRecoveryPage;
