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
import { useGetMirrorQuery, useUpdateMirrorMutation } from 'dogma/features/api/apiSlice';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { Deferred } from 'dogma/common/components/Deferred';
import { MirrorDto, MirrorRequest } from 'dogma/features/repo/settings/mirrors/MirrorRequest';
import MirrorForm from 'dogma/features/repo/settings/mirrors/MirrorForm';

const XdsMirrorEditPage = () => {
  const router = useRouter();
  const group = router.query.group as string | undefined;
  const id = router.query.id as string | undefined;

  const { data, isLoading, error } = useGetMirrorQuery(
    { projectName: '@xds', repoName: group!, id: id! },
    { skip: !group || !id },
  );
  const [updateMirror, { isLoading: isWaitingMutationResponse }] = useUpdateMirrorMutation();
  const dispatch = useAppDispatch();

  if (!group || !id) {
    return null;
  }

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
      mirrorRequest.projectName = '@xds';
      mirrorRequest.localRepo = group;

      const response = await updateMirror({
        projectName: '@xds',
        repoName: group,
        id,
        mirror: mirrorRequest,
      }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification(`Mirror '${mirror.id}' is updated`, 'Successfully updated', 'success'));
      onSuccess();
      Router.push(`/app/xds/mirrors/${encodeURIComponent(id)}?group=${encodeURIComponent(group)}`);
    } catch (error) {
      dispatch(newNotification('Failed to update the mirror', ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
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
            <BreadcrumbItem>
              <BreadcrumbLink
                as={RouteLink}
                href={`/app/xds/mirrors/${encodeURIComponent(id)}?group=${encodeURIComponent(group)}`}
              >
                {id}
              </BreadcrumbLink>
            </BreadcrumbItem>
            <BreadcrumbItem isCurrentPage>
              <BreadcrumbLink href="#">Edit</BreadcrumbLink>
            </BreadcrumbItem>
          </Breadcrumb>
          <MirrorForm
            projectName="@xds"
            repoName={group}
            defaultValue={data}
            onSubmit={onSubmit}
            isWaitingResponse={isWaitingMutationResponse}
            hideLocalPath
          />
        </>
      )}
    </Deferred>
  );
};

export default XdsMirrorEditPage;
