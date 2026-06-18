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
import {
  Alert,
  AlertIcon,
  Box,
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  Flex,
  Heading,
  Text,
} from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import { useRouter } from 'next/router';
import { useEffect } from 'react';
import { ResourceList } from 'dogma/features/xds/ResourceList';
import { PermissionsTab } from 'dogma/features/xds/PermissionsTab';
import { K8sAggregatorList } from 'dogma/features/xds/K8sAggregatorList';
import { CredentialsTab } from 'dogma/features/xds/CredentialsTab';
import { DangerZone } from 'dogma/features/xds/DangerZone';
import { Loading } from 'dogma/common/components/Loading';
import { XDS_RESOURCE_META } from 'dogma/features/xds/XdsTypes';
import { useXdsRoute } from 'dogma/common/useXdsRoute';
import { useGroupReadAccess } from 'dogma/common/useGroupReadAccess';
import { useGroupAdminAccess } from 'dogma/common/useGroupAdminAccess';
import { useGroupExists } from 'dogma/common/useGroupExists';

// Sections that manage group-level access and are therefore restricted to group admins.
const ADMIN_ONLY_SECTIONS = ['permissions', 'credentials', 'dangerZone'];

const GroupDetailPage = () => {
  const { group, section } = useXdsRoute();
  const router = useRouter();
  const { isLoading: groupLoading, exists } = useGroupExists(group);
  const { isLoading: accessLoading, hasAccess } = useGroupReadAccess(group);
  const { isLoading: adminLoading, isAdmin } = useGroupAdminAccess(group);

  // Only the Endpoints section is readable without READ access to the group. If a user without access lands on
  // an access-controlled section (e.g. via a bookmark or the default landing), send them to Endpoints instead
  // of showing a 403 error.
  const gatedSection = section !== 'endpoints';
  const redirectToEndpoints = !!group && !accessLoading && !hasAccess && gatedSection;
  // Admin-only sections are hidden from the sidebar for non-admins; also redirect them away when reached
  // directly via a bookmark or the URL. Users with READ access land on Listeners, the default resource view.
  const redirectFromAdminOnly = !!group && !adminLoading && !isAdmin && ADMIN_ONLY_SECTIONS.includes(section);
  const redirectTo = redirectToEndpoints ? 'endpoints' : redirectFromAdminOnly ? 'listeners' : null;
  useEffect(() => {
    if (redirectTo) {
      router.replace(`/app/xds/group?name=${encodeURIComponent(group as string)}&type=${redirectTo}`);
    }
  }, [redirectTo, group, router]);

  if (!group) {
    return null;
  }
  if (groupLoading) {
    return <Loading />;
  }
  if (!exists) {
    return (
      <Alert status="warning" borderRadius="md" alignItems="flex-start">
        <AlertIcon />
        <Box>
          <Text fontWeight="bold">Group not found</Text>
          <Text fontSize="sm">
            No group named &apos;{group}&apos; exists. It may have been deleted, or the name in the URL is
            incorrect.
          </Text>
        </Box>
      </Alert>
    );
  }
  if (redirectTo) {
    return null;
  }
  // Avoid briefly rendering an admin-only section before the admin check resolves.
  if (ADMIN_ONLY_SECTIONS.includes(section) && adminLoading) {
    return <Loading />;
  }
  const title =
    section === 'permissions'
      ? 'Permissions'
      : section === 'k8sAggregators'
        ? 'K8s Aggregators'
        : section === 'credentials'
          ? 'Credentials'
          : section === 'dangerZone'
            ? 'Danger Zone'
            : `${XDS_RESOURCE_META[section].label}s`;
  return (
    <Box>
      <Breadcrumb mb={4} color="gray.500" fontSize="sm">
        <BreadcrumbItem>
          <BreadcrumbLink as={RouteLink} href="/app/xds">
            Groups
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem>
          <BreadcrumbLink
            as={RouteLink}
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=listeners`}
          >
            {group}
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <BreadcrumbLink href="#">{title}</BreadcrumbLink>
        </BreadcrumbItem>
      </Breadcrumb>
      <Flex align="center" mb={6}>
        <Heading size="lg">
          {title}
          {(section === 'listeners' ||
            section === 'routes' ||
            section === 'clusters' ||
            section === 'endpoints') && (
            <Box as="span" ml={2} fontSize="md" color="gray.400">
              {XDS_RESOURCE_META[section].acronym}
            </Box>
          )}
        </Heading>
      </Flex>
      {section === 'permissions' ? (
        <PermissionsTab group={group} />
      ) : section === 'k8sAggregators' ? (
        <K8sAggregatorList group={group} />
      ) : section === 'credentials' ? (
        <CredentialsTab group={group} />
      ) : section === 'dangerZone' ? (
        <DangerZone group={group} />
      ) : (
        <ResourceList group={group} type={section} />
      )}
    </Box>
  );
};

export default GroupDetailPage;
