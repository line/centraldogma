/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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
  Button,
  Center,
  Code,
  Heading,
  HStack,
  Icon,
  Link,
  Spacer,
  Table,
  TableContainer,
  Tbody,
  Td,
  Text,
  Tr,
  VStack,
} from '@chakra-ui/react';
import { EditIcon } from '@chakra-ui/icons';
import React, { ReactNode } from 'react';
import { IconType } from 'react-icons';
import { HiOutlineIdentification } from 'react-icons/hi';

import { MdOutlineDescription, MdPolicy } from 'react-icons/md';
import { RiSortNumberAsc } from 'react-icons/ri';
import { MirrorAccessControl } from 'dogma/features/settings/mirror-access/MirrorAccessControl';
import { LuRegex } from 'react-icons/lu';
import { FaUser } from 'react-icons/fa';
import { IoCalendarNumberOutline } from 'react-icons/io5';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { GiMirrorMirror } from 'react-icons/gi';

const HeadRow = ({ children }: { children: ReactNode }) => (
  <Td width="250px" fontWeight="bold">
    {children}
  </Td>
);

interface MirrorAccessControlViewProps {
  mirrorAccessControl: MirrorAccessControl;
}

const AlignedIcon = ({ as }: { as: IconType }) => <Icon as={as} marginBottom="-4px" marginRight={2} />;

const MirrorAccessControlView = ({ mirrorAccessControl }: MirrorAccessControlViewProps) => {
  return (
    <Center>
      <VStack width="90%" align="left">
        <Heading size="lg" mb={4}>
          <HStack>
            <Box color="teal">
              <GiMirrorMirror />
            </Box>
            <Box color={'teal'}>Mirror access control</Box>
          </HStack>
        </Heading>
        <Spacer />
        <TableContainer>
          <Table fontSize={'lg'} variant="unstyled">
            <Tbody>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={HiOutlineIdentification} /> ID
                </HeadRow>
                <Td fontWeight="semibold">{mirrorAccessControl.id}</Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={LuRegex} /> Git URI Pattern
                </HeadRow>
                <Td>
                  <Code p={1.5}>{mirrorAccessControl.targetPattern}</Code>
                </Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={MdPolicy} /> Access
                </HeadRow>
                <Td>
                  <Badge colorScheme={mirrorAccessControl.allow ? 'blue' : 'red'}>
                    {mirrorAccessControl.allow ? 'Allowed' : 'Disallowed'}
                  </Badge>
                </Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={RiSortNumberAsc} /> Order
                </HeadRow>
                <Td>{mirrorAccessControl.order}</Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={MdOutlineDescription} /> Description
                </HeadRow>
                <Td>
                  <Text>{mirrorAccessControl.description}</Text>
                </Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={FaUser} /> Created By
                </HeadRow>
                <Td>{mirrorAccessControl.creation.user}</Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={IoCalendarNumberOutline} /> Created At
                </HeadRow>
                <Td>
                  <DateWithTooltip date={mirrorAccessControl.creation.timestamp} />
                </Td>
              </Tr>
            </Tbody>
          </Table>
        </TableContainer>

        <Center mt={10}>
          <Link href={`/app/settings/mirror-access/${mirrorAccessControl.id}/edit`}>
            <Button colorScheme="teal">
              <EditIcon mr={2} />
              Edit Mirror Access Control
            </Button>
          </Link>
        </Center>
      </VStack>
    </Center>
  );
};

export default MirrorAccessControlView;
