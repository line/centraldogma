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
import { Breadcrumb, BreadcrumbItem, BreadcrumbLink } from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import Router, { useRouter } from 'next/router';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { SerializedError } from '@reduxjs/toolkit';
import { useAddNewRepoCredentialMutation } from 'dogma/features/api/apiSlice';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import {
  CredentialDto,
  CreateCredentialRequestDto,
} from 'dogma/features/project/settings/credentials/CredentialDto';
import CredentialForm from 'dogma/features/project/settings/credentials/CredentialForm';

const EMPTY_CREDENTIAL: CredentialDto = {
  id: '',
  name: '',
  type: 'SSH_KEY',
};

const XdsNewCredentialPage = () => {
  const router = useRouter();
  const group = router.query.group as string | undefined;
  const dispatch = useAppDispatch();
  const [addNewCredential, { isLoading }] = useAddNewRepoCredentialMutation();

  if (!group) {
    return null;
  }

  const onSubmit = async (credential: CredentialDto, onSuccess: () => void) => {
    try {
      const credentialRequest: CreateCredentialRequestDto = {
        credentialId: credential.id,
        credential: credential,
      };
      const response = await addNewCredential({
        projectName: '@xds',
        credentialRequest,
        repoName: group,
      }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification('New credential is created', 'Successfully created', 'success'));
      onSuccess();
      Router.push(`/app/xds/group?name=${encodeURIComponent(group)}&type=credentials`);
    } catch (error) {
      dispatch(newNotification('Failed to create a new credential', ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <>
      <Breadcrumb mb={4} color="gray.500" fontSize="sm">
        <BreadcrumbItem>
          <BreadcrumbLink as={RouteLink} href="/app/xds">
            Groups
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem>
          <BreadcrumbLink
            as={RouteLink}
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=overview`}
          >
            {group}
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem>
          <BreadcrumbLink
            as={RouteLink}
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=credentials`}
          >
            Credentials
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <BreadcrumbLink href="#">New Credential</BreadcrumbLink>
        </BreadcrumbItem>
      </Breadcrumb>
      <CredentialForm
        projectName="@xds"
        repoName={group}
        defaultValue={EMPTY_CREDENTIAL}
        onSubmit={onSubmit}
        isWaitingResponse={isLoading}
        hideScope
      />
    </>
  );
};

export default XdsNewCredentialPage;
