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
import { Box, Divider, Flex, HStack, Icon, Link, Text, useColorModeValue, VStack } from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import { IconType } from 'react-icons';
import { FiBox } from 'react-icons/fi';
import { HiOutlineDocumentText } from 'react-icons/hi2';
import {
  MdOutlineRoute,
  MdLockOutline,
  MdVpnKey,
  MdWarningAmber,
  MdHistory,
  MdDashboard,
  MdAccountTree,
  MdSync,
} from 'react-icons/md';
import { TbServer2, TbRouteSquare } from 'react-icons/tb';
import { SiKubernetes } from 'react-icons/si';
import { XDS_RESOURCE_META, XDS_RESOURCE_TYPES } from 'dogma/features/xds/XdsTypes';
import { GroupSelector } from 'dogma/features/xds/GroupSelector';
import { useXdsRoute, XdsSection } from 'dogma/features/xds/useXdsRoute';
import { useGroupReadAccess } from 'dogma/features/xds/useGroupReadAccess';
import { useGroupAdminAccess } from 'dogma/features/xds/useGroupAdminAccess';
import { useGroupExists } from 'dogma/features/xds/useGroupExists';

interface NavItemProps {
  href: string;
  label: string;
  icon: IconType;
  active: boolean;
}

const NavItem = ({ href, label, icon, active }: NavItemProps) => {
  const activeBg = useColorModeValue('teal.50', 'teal.900');
  const activeColor = useColorModeValue('teal.700', 'teal.200');
  const hoverBg = useColorModeValue('gray.100', 'gray.700');
  return (
    <Link
      as={RouteLink}
      href={href}
      w="100%"
      px={3}
      py={2}
      borderRadius="md"
      _hover={{ textDecoration: 'none', bg: active ? activeBg : hoverBg }}
      bg={active ? activeBg : 'transparent'}
      color={active ? activeColor : 'inherit'}
      fontWeight={active ? 'bold' : 'medium'}
    >
      <HStack spacing={3}>
        <Icon as={icon} boxSize={5} />
        <Text>{label}</Text>
      </HStack>
    </Link>
  );
};

const SECTION_ICONS: Record<XdsSection, IconType> = {
  overview: MdDashboard,
  references: MdAccountTree,
  listeners: TbServer2,
  routes: MdOutlineRoute,
  clusters: FiBox,
  endpoints: TbRouteSquare,
  k8sAggregators: SiKubernetes,
  credentials: MdVpnKey,
  permissions: MdLockOutline,
  dangerZone: MdWarningAmber,
  history: MdHistory,
  mirroring: MdSync,
};

export const Sidebar = () => {
  const { group, section } = useXdsRoute();
  const { isLoading: groupLoading, exists } = useGroupExists(group);
  const { isLoading: accessLoading, hasAccess } = useGroupReadAccess(group);
  const { isAdmin } = useGroupAdminAccess(group);
  const bg = useColorModeValue('gray.50', 'gray.900');
  const borderColor = useColorModeValue('gray.200', 'gray.700');
  const headerColor = useColorModeValue('gray.500', 'gray.400');

  // The sidebar navigates a selected group's resources. On the groups list (no group selected), or when the
  // group in the URL does not exist, there is nothing to show, so the whole sidebar is hidden and the page
  // takes the full width. Navigating back to the groups list is available from the page breadcrumb.
  if (!group || (!groupLoading && !exists)) {
    return null;
  }

  // A user without READ access to the group can still read its endpoints (EDS is not access-controlled), so
  // only the Endpoints section is shown for such a group. While the access check is in flight, show every
  // section optimistically to avoid hiding sections from users who do have access.
  const endpointsOnly = !accessLoading && !hasAccess;

  return (
    <Flex
      as="nav"
      direction="column"
      w="260px"
      minH="calc(100vh - 60px)"
      bg={bg}
      borderRightWidth="1px"
      borderColor={borderColor}
      px={3}
      py={5}
    >
      {/* Group switcher: replaces the old top-bar dropdown, letting users hop between groups or back to the
          full groups list without leaving the sidebar. */}
      <Box px={1} mb={4}>
        <GroupSelector currentGroup={group} />
      </Box>
      {/* Group overview is a READ-gated summary, shown above the per-type resource navigation. */}
      {!endpointsOnly && (
        <Box mb={3}>
          <NavItem
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=overview`}
            label="Overview"
            icon={SECTION_ICONS.overview}
            active={section === 'overview'}
          />
        </Box>
      )}
      <Text px={3} mb={2} fontSize="xs" fontWeight="bold" color={headerColor} letterSpacing="wide">
        RESOURCES
      </Text>
      <VStack spacing={1} align="stretch">
        {XDS_RESOURCE_TYPES.filter((type) => !endpointsOnly || type === 'endpoints').map((type) => (
          <NavItem
            key={type}
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=${type}`}
            label={`${XDS_RESOURCE_META[type].label}s`}
            icon={SECTION_ICONS[type]}
            active={section === type}
          />
        ))}
        {!endpointsOnly && (
          <NavItem
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=k8sAggregators`}
            label="K8s Aggregators"
            icon={SECTION_ICONS.k8sAggregators}
            active={section === 'k8sAggregators'}
          />
        )}
        {/* Reference graph (search + reverse references + dangling detection), readable with READ access. */}
        {!endpointsOnly && (
          <NavItem
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=references`}
            label="References"
            icon={SECTION_ICONS.references}
            active={section === 'references'}
          />
        )}
        {/* The group's resource change history (commit log), readable by anyone with READ access. */}
        {!endpointsOnly && (
          <NavItem
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=history`}
            label="History"
            icon={SECTION_ICONS.history}
            active={section === 'history'}
          />
        )}
        {/* Mirror configuration for the group's backing repository, visible only to admins. */}
        {!endpointsOnly && isAdmin && (
          <NavItem
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=mirroring`}
            label="Mirroring"
            icon={SECTION_ICONS.mirroring}
            active={section === 'mirroring'}
          />
        )}
        {/* Credentials and Permissions manage group-level access, so they are shown only to group admins. */}
        {!endpointsOnly && isAdmin && (
          <NavItem
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=credentials`}
            label="Credentials"
            icon={SECTION_ICONS.credentials}
            active={section === 'credentials'}
          />
        )}
        {!endpointsOnly && isAdmin && (
          <NavItem
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=permissions`}
            label="Permissions"
            icon={SECTION_ICONS.permissions}
            active={section === 'permissions'}
          />
        )}
        {/* Group-level destructive actions live in one place (Danger Zone), shown only to admins. */}
        {!endpointsOnly && isAdmin && (
          <NavItem
            href={`/app/xds/group?name=${encodeURIComponent(group)}&type=dangerZone`}
            label="Danger Zone"
            icon={SECTION_ICONS.dangerZone}
            active={section === 'dangerZone'}
          />
        )}
      </VStack>

      <Box flex={1} />
      <Divider my={3} />
      <Link
        href="https://line.github.io/centraldogma/"
        isExternal
        px={3}
        py={2}
        _hover={{ textDecoration: 'none' }}
      >
        <HStack spacing={3} color={headerColor}>
          <Icon as={HiOutlineDocumentText} boxSize={5} />
          <Text>Documentation</Text>
        </HStack>
      </Link>
    </Flex>
  );
};
