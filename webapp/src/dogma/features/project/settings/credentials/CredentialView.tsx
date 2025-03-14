/*
 * Copyright 2023 LINE Corporation
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
  Flex,
  Heading,
  HStack,
  Icon,
  Link,
  Spacer,
  Table,
  TableContainer,
  Tbody,
  Td,
  Tr,
  VStack,
} from '@chakra-ui/react';
import { GoKey, GoLock } from 'react-icons/go';
import { EditIcon } from '@chakra-ui/icons';
import React, { ReactNode, useState } from 'react';
import { AppDispatch } from 'dogma/store';
import { useAppDispatch, useAppSelector } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import { IconType } from 'react-icons';
import { HiOutlineIdentification, HiOutlineUser } from 'react-icons/hi';

import { MdPublic } from 'react-icons/md';
import { RiGitRepositoryPrivateLine } from 'react-icons/ri';
import { CredentialDto } from 'dogma/features/project/settings/credentials/CredentialDto';
import { CiLock } from 'react-icons/ci';
import { FiBox } from 'react-icons/fi';
import { GoRepo } from 'react-icons/go';

const HeadRow = ({ children }: { children: ReactNode }) => (
  <Td width="250px" fontWeight="bold">
    {children}
  </Td>
);

interface SecretViewerProps {
  dispatch: AppDispatch;
  secretProvider: () => string;
}

const SecretViewer = ({ dispatch, secretProvider }: SecretViewerProps) => {
  const [showSecret, setShowSecret] = useState(false);
  const systemAdmin = useAppSelector((state) => state.auth.user.systemAdmin);
  return (
    <Flex wrap="wrap">
      <Code width="500px" p={10} whiteSpace="pre-wrap">
        {showSecret ? secretProvider() : '****'}
      </Code>

      {systemAdmin ? (
        <div>
          <Button
            aria-label="Show key"
            size="xs"
            color="teal.500"
            marginTop={1}
            bottom={-2}
            right={28}
            onClick={() => setShowSecret(!showSecret)}
          >
            {showSecret ? 'Hide' : 'Show'}
          </Button>
          <Button
            aria-label="Copy key"
            size="xs"
            color="purple"
            marginTop={1}
            bottom={-2}
            right={28}
            onClick={async () => {
              await navigator.clipboard.writeText(secretProvider());
              dispatch(newNotification('', 'copied to clipboard', 'success'));
            }}
          >
            Copy
          </Button>
        </div>
      ) : null}
    </Flex>
  );
};

interface CredentialViewProps {
  projectName: string;
  repoName?: string;
  credential: CredentialDto;
}

const AlignedIcon = ({ as }: { as: IconType }) => <Icon as={as} marginBottom="-4px" marginRight={2} />;

const CredentialView = ({ projectName, repoName, credential }: CredentialViewProps) => {
  const dispatch = useAppDispatch();

  return (
    <Center>
      <VStack width="90%" align="left">
        <Heading size="lg" mb={4}>
          <HStack>
            <Box color="teal">
              <CiLock />
            </Box>
            <Box color={'teal'}>Credential</Box>
            <Box>{credential.id}</Box>
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
                  <AlignedIcon as={HiOutlineIdentification} /> Credential ID
                </HeadRow>
                <Td fontWeight="semibold">{credential.id}</Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={GoLock} /> Authentication Type
                </HeadRow>
                <Td>
                  <Badge colorScheme="purple">{credential.type}</Badge>
                </Td>
              </Tr>
              {credential.type === 'SSH_KEY' && (
                <>
                  <Tr>
                    <HeadRow>
                      <AlignedIcon as={HiOutlineUser} /> Username
                    </HeadRow>
                    <Td>{credential.username}</Td>
                  </Tr>
                  <Tr>
                    <HeadRow>
                      <AlignedIcon as={MdPublic} /> Public key
                    </HeadRow>
                    <Td>
                      <SecretViewer dispatch={dispatch} secretProvider={() => credential.publicKey} />
                    </Td>
                  </Tr>
                  <Tr>
                    <HeadRow>
                      <AlignedIcon as={RiGitRepositoryPrivateLine} /> Private key
                    </HeadRow>
                    <Td>
                      <SecretViewer dispatch={dispatch} secretProvider={() => credential.privateKey} />
                    </Td>
                  </Tr>
                  <Tr>
                    <HeadRow>
                      <AlignedIcon as={GoKey} /> Passphrase
                    </HeadRow>
                    <Td>
                      {credential.passphrase ? (
                        <SecretViewer dispatch={dispatch} secretProvider={() => credential.passphrase} />
                      ) : (
                        ''
                      )}
                    </Td>
                  </Tr>
                </>
              )}
              {credential.type === 'PASSWORD' && (
                <>
                  <Tr>
                    <HeadRow>
                      <AlignedIcon as={HiOutlineUser} /> Username
                    </HeadRow>
                    <Td>{credential.username}</Td>
                  </Tr>
                  <Tr>
                    <HeadRow>
                      <AlignedIcon as={GoKey} /> Password
                    </HeadRow>
                    <Td>
                      <SecretViewer dispatch={dispatch} secretProvider={() => credential.password} />
                    </Td>
                  </Tr>
                </>
              )}

              {credential.type === 'ACCESS_TOKEN' && (
                <Tr>
                  <HeadRow>
                    <AlignedIcon as={GoKey} /> Access Token
                  </HeadRow>
                  <Td>
                    <SecretViewer dispatch={dispatch} secretProvider={() => credential.accessToken} />
                  </Td>
                </Tr>
              )}
            </Tbody>
          </Table>
        </TableContainer>

        <Center mt={10}>
          <Link
            href={`/app/projects/${projectName}${repoName ? `/repos/${repoName}` : ''}/settings/credentials/${credential.id}/edit`}
          >
            <Button colorScheme="teal">
              <EditIcon mr={2} />
              Edit Credential
            </Button>
          </Link>
        </Center>
      </VStack>
    </Center>
  );
};

export default CredentialView;
