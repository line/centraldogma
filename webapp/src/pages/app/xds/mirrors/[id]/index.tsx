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
import { useRouter } from 'next/router';
import { useGetMirrorQuery } from 'dogma/features/api/apiSlice';
import { Deferred } from 'dogma/common/components/Deferred';
import MirrorView from 'dogma/features/repo/settings/mirrors/MirrorView';

const XdsMirrorViewPage = () => {
  const router = useRouter();
  const group = router.query.group as string | undefined;
  const id = router.query.id as string | undefined;

  const {
    data: mirror,
    isLoading,
    error,
  } = useGetMirrorQuery({ projectName: '@xds', repoName: group!, id: id! }, { skip: !group || !id });

  if (!group || !id) {
    return null;
  }

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
            <BreadcrumbItem isCurrentPage>
              <BreadcrumbLink href="#">{id}</BreadcrumbLink>
            </BreadcrumbItem>
          </Breadcrumb>
          <MirrorView
            projectName="@xds"
            repoName={group}
            mirror={mirror}
            editHref={`/app/xds/mirrors/${encodeURIComponent(id)}/edit?group=${encodeURIComponent(group)}`}
          />
        </>
      )}
    </Deferred>
  );
};

export default XdsMirrorViewPage;
