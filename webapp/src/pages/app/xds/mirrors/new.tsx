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
import { UseFormSetError } from 'react-hook-form';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { SerializedError } from '@reduxjs/toolkit';
import { useAddNewMirrorMutation } from 'dogma/features/api/apiSlice';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { MirrorRequest } from 'dogma/features/repo/settings/mirrors/MirrorRequest';
import MirrorForm from 'dogma/features/repo/settings/mirrors/MirrorForm';

const XdsNewMirrorPage = () => {
  const router = useRouter();
  const group = router.query.group as string | undefined;
  const dispatch = useAppDispatch();
  const [addNewMirror, { isLoading }] = useAddNewMirrorMutation();

  if (!group) {
    return null;
  }

  const emptyMirror: MirrorRequest = {
    id: '',
    direction: 'REMOTE_TO_LOCAL',
    schedule: '0 * * * * ?',
    projectName: '@xds',
    localRepo: group,
    localPath: '/',
    remoteScheme: '',
    remoteUrl: '',
    remoteBranch: 'main',
    remotePath: '/',
    credentialName: null,
    gitignore: null,
    enabled: false,
  };

  const onSubmit = async (
    formData: MirrorRequest,
    onSuccess: () => void,
    setError: UseFormSetError<MirrorRequest>,
  ) => {
    try {
      formData.projectName = '@xds';
      formData.localRepo = group;
      if (formData.remoteScheme.startsWith('git') && !formData.remoteUrl.endsWith('.git')) {
        setError('remoteUrl', { type: 'manual', message: "The remote path must end with '.git'" });
        return;
      }
      const response = await addNewMirror(formData).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification('New mirror is created', 'Successfully created', 'success'));
      onSuccess();
      Router.push(`/app/xds/group?name=${encodeURIComponent(group)}&type=mirroring`);
    } catch (error) {
      dispatch(newNotification('Failed to create a new mirror', ErrorMessageParser.parse(error), 'error'));
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
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=mirroring`}
          >
            Mirroring
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <BreadcrumbLink href="#">New Mirror</BreadcrumbLink>
        </BreadcrumbItem>
      </Breadcrumb>
      <MirrorForm
        projectName="@xds"
        repoName={group}
        defaultValue={emptyMirror}
        onSubmit={onSubmit}
        isWaitingResponse={isLoading}
        hideLocalPath
      />
    </>
  );
};

export default XdsNewMirrorPage;
