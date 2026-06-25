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
  Heading,
  Tab,
  TabList,
  TabPanel,
  TabPanels,
  Tabs,
  Text,
} from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import { Deferred } from 'dogma/common/components/Deferred';
import { useGetXdsClientsQuery } from 'dogma/features/api/apiSlice';
import { ClientStatusTable } from 'dogma/features/xds/ClientStatusTable';
import { SnapshotViewer } from 'dogma/features/xds/SnapshotViewer';
import { useAppSelector } from 'dogma/hooks';

const ClientsTab = () => {
  const { data, isLoading, error } = useGetXdsClientsQuery(undefined, {
    refetchOnMountOrArgChange: true,
  });
  return (
    <Box>
      <Text fontSize="sm" color="gray.500" mb={2}>
        Clients connected to this server, with the version each has ACKed per resource type and the reason for
        any NACK. State is in-memory and reflects only this server instance.
      </Text>
      <Deferred isLoading={isLoading} error={error}>
        {() => <ClientStatusTable clients={data || []} />}
      </Deferred>
    </Box>
  );
};

const XdsControlPlanePage = () => {
  const { user } = useAppSelector((state) => state.auth);
  return (
    <Box p="2">
      <Breadcrumb mb={4} color="gray.500">
        <BreadcrumbItem>
          <BreadcrumbLink as={RouteLink} href="/app/xds">
            xDS Groups
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <BreadcrumbLink href="#">Control Plane</BreadcrumbLink>
        </BreadcrumbItem>
      </Breadcrumb>
      <Heading size="lg" color="teal" mb={6}>
        xDS Control Plane
      </Heading>
      {/* This view exposes sensitive operational data (connected nodes and served snapshots), so it is gated
          to system administrators. The backend APIs are independently gated, so this is only a UX guard. */}
      {!user?.systemAdmin ? (
        <Alert status="warning" borderRadius="md">
          <AlertIcon />
          You must be a system administrator to view the xDS control plane.
        </Alert>
      ) : (
        <Tabs variant="line" size="md" isLazy>
          <TabList>
            <Tab>Clients</Tab>
            <Tab>Snapshot</Tab>
          </TabList>
          <TabPanels>
            <TabPanel px={0}>
              <ClientsTab />
            </TabPanel>
            <TabPanel px={0}>
              <SnapshotViewer />
            </TabPanel>
          </TabPanels>
        </Tabs>
      )}
    </Box>
  );
};

export default XdsControlPlanePage;
