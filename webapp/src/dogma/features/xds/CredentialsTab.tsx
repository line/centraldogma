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
import { Alert, AlertIcon, Box, Button, Flex, Spacer } from '@chakra-ui/react';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { IoAddCircleOutline } from 'react-icons/io5';
import { default as RouteLink } from 'next/link';
import { useGetRepoCredentialsQuery, useDeleteRepoCredentialMutation } from 'dogma/features/api/apiSlice';
import CredentialList from 'dogma/features/project/settings/credentials/CredentialList';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';

export const CredentialsTab = ({ group }: { group: string }) => {
  const dispatch = useAppDispatch();
  const { data, isLoading, error } = useGetRepoCredentialsQuery(
    { projectName: '@xds', repoName: group },
    { refetchOnMountOrArgChange: true },
  );
  const [deleteCredentialMutation, { isLoading: isDeleting }] = useDeleteRepoCredentialMutation();

  if (isLoading) {
    return null;
  }
  if (error) {
    const status = (error as FetchBaseQueryError).status;
    if (status === 403) {
      return (
        <Alert status="info" borderRadius="md">
          <AlertIcon />
          Managing credentials requires the ADMIN role on this group.
        </Alert>
      );
    }
    return (
      <Alert status="error" borderRadius="md">
        <AlertIcon />
        Failed to load credentials.
      </Alert>
    );
  }

  return (
    <Box>
      <Flex mb={4}>
        <Spacer />
        <Button
          as={RouteLink}
          href={`/app/xds/credentials/new?group=${encodeURIComponent(group)}`}
          size="sm"
          colorScheme="teal"
          leftIcon={<IoAddCircleOutline />}
        >
          New Credential
        </Button>
      </Flex>
      <CredentialList
        projectName="@xds"
        repoName={group}
        credentials={data}
        deleteCredential={async (projectName, id, repoName) => {
          try {
            await deleteCredentialMutation({ projectName, id, repoName }).unwrap();
            dispatch(newNotification('Credential deleted', `Credential '${id}' is deleted`, 'success'));
          } catch (err) {
            dispatch(
              newNotification('Failed to delete the credential', ErrorMessageParser.parse(err), 'error'),
            );
          }
        }}
        isLoading={isDeleting}
        buildDetailUrl={(id) =>
          `/app/xds/credentials/${encodeURIComponent(id)}?group=${encodeURIComponent(group)}`
        }
      />
    </Box>
  );
};
