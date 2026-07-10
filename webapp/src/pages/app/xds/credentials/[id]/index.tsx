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
import { Breadcrumb, BreadcrumbItem, BreadcrumbLink, Flex, Spacer } from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import { useRouter } from 'next/router';
import { useGetRepoCredentialQuery } from 'dogma/features/api/apiSlice';
import { Deferred } from 'dogma/common/components/Deferred';
import CredentialView from 'dogma/features/project/settings/credentials/CredentialView';

const XdsCredentialViewPage = () => {
  const router = useRouter();
  const group = router.query.group as string | undefined;
  const id = router.query.id as string | undefined;

  const { data, isLoading, error } = useGetRepoCredentialQuery(
    { projectName: '@xds', repoName: group, id },
    { skip: !group || !id },
  );

  if (!group || !id) {
    return null;
  }

  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <>
          <Flex>
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
                <BreadcrumbLink href="#">{id}</BreadcrumbLink>
              </BreadcrumbItem>
            </Breadcrumb>
            <Spacer />
          </Flex>
          <CredentialView
            projectName="@xds"
            repoName={group}
            credential={data}
            editUrl={`/app/xds/credentials/${encodeURIComponent(id)}/edit?group=${encodeURIComponent(group)}`}
            hideScope
          />
        </>
      )}
    </Deferred>
  );
};

export default XdsCredentialViewPage;
