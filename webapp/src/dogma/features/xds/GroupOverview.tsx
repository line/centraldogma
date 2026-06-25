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
  Badge,
  Box,
  HStack,
  Link,
  LinkBox,
  LinkOverlay,
  SimpleGrid,
  Stat,
  StatLabel,
  StatNumber,
  useColorModeValue,
} from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import { useListK8sAggregatorsQuery, useListResourcesQuery } from 'dogma/features/xds/xdsApiSlice';
import { XDS_RESOURCE_META, XDS_RESOURCE_TYPES, XdsResourceType } from 'dogma/features/xds/XdsTypes';

const sectionHref = (group: string, type: string) =>
  `/app/xds/group?name=${encodeURIComponent(group)}&type=${type}`;

// A clickable card showing the number of resources of one type in the group.
const ResourceCountCard = ({ group, type }: { group: string; type: XdsResourceType }) => {
  const { data, isLoading } = useListResourcesQuery({ group, type }, { refetchOnMountOrArgChange: true });
  const meta = XDS_RESOURCE_META[type];
  const border = useColorModeValue('gray.200', 'gray.700');
  const hoverBg = useColorModeValue('gray.50', 'gray.700');
  return (
    <LinkBox
      as={Stat}
      borderWidth="1px"
      borderColor={border}
      borderRadius="md"
      px={4}
      py={3}
      _hover={{ bg: hoverBg }}
    >
      <StatLabel>
        <LinkOverlay as={RouteLink} href={sectionHref(group, type)}>
          {meta.label}s
        </LinkOverlay>
        <Badge ml={2} colorScheme="purple" fontSize="0.6rem">
          {meta.acronym}
        </Badge>
      </StatLabel>
      <StatNumber>{isLoading ? '…' : data?.length ?? 0}</StatNumber>
    </LinkBox>
  );
};

const K8sAggregatorCountCard = ({ group }: { group: string }) => {
  const { data, isLoading } = useListK8sAggregatorsQuery({ group }, { refetchOnMountOrArgChange: true });
  const border = useColorModeValue('gray.200', 'gray.700');
  const hoverBg = useColorModeValue('gray.50', 'gray.700');
  return (
    <LinkBox
      as={Stat}
      borderWidth="1px"
      borderColor={border}
      borderRadius="md"
      px={4}
      py={3}
      _hover={{ bg: hoverBg }}
    >
      <StatLabel>
        <LinkOverlay as={RouteLink} href={sectionHref(group, 'k8sAggregators')}>
          K8s Aggregators
        </LinkOverlay>
      </StatLabel>
      <StatNumber>{isLoading ? '…' : data?.length ?? 0}</StatNumber>
    </LinkBox>
  );
};

export const GroupOverview = ({ group }: { group: string }) => {
  return (
    <Box>
      <SimpleGrid columns={{ base: 2, md: 3, lg: 5 }} spacing={4}>
        {XDS_RESOURCE_TYPES.map((type) => (
          <ResourceCountCard key={type} group={group} type={type} />
        ))}
        <K8sAggregatorCountCard group={group} />
      </SimpleGrid>

      <HStack mt={6} spacing={4} color="gray.500" fontSize="sm">
        <Link as={RouteLink} href={sectionHref(group, 'references')} color="teal">
          View references &amp; consistency →
        </Link>
        <Link as={RouteLink} href={sectionHref(group, 'history')} color="teal">
          View change history →
        </Link>
      </HStack>
    </Box>
  );
};
