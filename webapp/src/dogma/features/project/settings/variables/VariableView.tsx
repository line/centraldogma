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
import { GoRepo } from 'react-icons/go';
import { EditIcon } from '@chakra-ui/icons';
import React, { ReactNode } from 'react';
import { IconType } from 'react-icons';
import { HiOutlineIdentification, HiVariable } from 'react-icons/hi';
import { VariableDto } from 'dogma/features/project/settings/variables/VariableDto';
import { CiText } from 'react-icons/ci';
import { FiBox, FiCodesandbox, FiInfo } from 'react-icons/fi';
import { TbJson } from 'react-icons/tb';
import { FaRegUserCircle } from 'react-icons/fa';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { IoMdTime } from 'react-icons/io';
import Prism from 'prismjs';
import 'prismjs/components/prism-json';
import 'prismjs/themes/prism.css';

const HeadRow = ({ children }: { children: ReactNode }) => (
  <Td width="250px" fontWeight="semibold">
    {children}
  </Td>
);

interface VariableViewProps {
  projectName: string;
  repoName?: string;
  variable: VariableDto;
}

const AlignedIcon = ({ as }: { as: IconType }) => <Icon as={as} marginBottom="-4px" marginRight={2} />;

function highlight(str: string, isJson: boolean): React.JSX.Element {
  const preStyle: React.CSSProperties = {
    display: 'block',
    border: '1px solid',
    borderColor: 'gray.200',
    borderRadius: '5px',
    padding: '15px',
    whiteSpace: 'pre-wrap',
  };

  if (!isJson) {
    return (
      <Code as="pre" style={preStyle}>
        {str}
      </Code>
    );
  }
  try {
    return (
      <Code
        as="pre"
        style={preStyle}
        dangerouslySetInnerHTML={{ __html: Prism.highlight(str, Prism.languages.json, 'json') }}
      />
    );
  } catch (e) {
    return <pre style={preStyle}>{str}</pre>;
  }
}

const VariableView = ({ projectName, repoName, variable }: VariableViewProps) => {
  return (
    <Center>
      <VStack width="90%" align="left">
        <Heading size="lg" mb={4}>
          <HStack>
            <Box color="teal" marginBottom="-4px">
              <HiVariable />
            </Box>
            <Box color={'teal'}>Variable</Box>
            <Box>{variable.id}</Box>
          </HStack>
        </Heading>
        <Spacer />
        <TableContainer>
          <Table fontSize={'lg'} variant="unstyled">
            <Tbody>
              {repoName ? (
                <Tr>
                  <HeadRow>
                    <AlignedIcon as={GoRepo} /> Repository
                  </HeadRow>
                  <Td fontWeight="semibold">{repoName}</Td>
                </Tr>
              ) : (
                <Tr>
                  <HeadRow>
                    <AlignedIcon as={FiBox} /> Project
                  </HeadRow>
                  <Td fontWeight="semibold">{projectName}</Td>
                </Tr>
              )}
              <Tr>
                <HeadRow>
                  <AlignedIcon as={HiOutlineIdentification} /> Variable ID
                </HeadRow>
                <Td fontWeight="semibold">{variable.id}</Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={FiCodesandbox} /> Variable Type
                </HeadRow>
                <Td>
                  {variable.type === 'STRING' ? (
                    <Badge colorScheme="blue">{variable.type}</Badge>
                  ) : (
                    <Badge colorScheme="green">{variable.type}</Badge>
                  )}
                </Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={variable.type === 'STRING' ? CiText : TbJson} /> Value
                </HeadRow>
                <Td>{highlight(variable.value, variable.type === 'JSON')}</Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={FiInfo} /> Description
                </HeadRow>
                <Td>
                  <Text>{variable.description}</Text>
                </Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={FaRegUserCircle} /> Modified By
                </HeadRow>
                <Td>
                  <Text>{variable.creation.user}</Text>
                </Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={IoMdTime} /> Modified At
                </HeadRow>
                <Td>
                  <DateWithTooltip date={variable.creation.timestamp} />
                </Td>
              </Tr>
            </Tbody>
          </Table>
        </TableContainer>

        <Center mt={10}>
          <Link
            href={`/app/projects/${projectName}${repoName ? `/repos/${repoName}` : ''}/settings/variables/${variable.id}/edit`}
          >
            <Button colorScheme="teal">
              <EditIcon mr={2} />
              Edit Variable
            </Button>
          </Link>
        </Center>
      </VStack>
    </Center>
  );
};

export default VariableView;
