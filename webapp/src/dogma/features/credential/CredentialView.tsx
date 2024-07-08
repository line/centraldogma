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
  Button,
  Center,
  Code,
  Flex,
  Heading,
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
import { CredentialDto } from 'dogma/features/credential/CredentialDto';
import { AppDispatch, useAppDispatch } from 'dogma/store';
import { createMessage } from 'dogma/features/message/messageSlice';
import { IconType } from 'react-icons';
import { HiOutlineIdentification, HiOutlineUser } from 'react-icons/hi';
import { GrOrganization } from 'react-icons/gr';

import { VscRegex } from 'react-icons/vsc';
import { MdPublic } from 'react-icons/md';
import { RiGitRepositoryPrivateLine } from 'react-icons/ri';

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
  return (
    <Flex wrap="wrap">
      <Code width="500px" p={10} whiteSpace="pre-wrap">
        {showSecret ? secretProvider() : '********'}
      </Code>
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
          dispatch(createMessage({ title: '', text: 'copied to clipboard', type: 'success' }));
        }}
      >
        Copy
      </Button>
    </Flex>
  );
};

interface CredentialViewProps {
  projectName: string;
  credential: CredentialDto;
}

const AlignedIcon = ({ as }: { as: IconType }) => <Icon as={as} marginBottom="-4px" marginRight={2} />;

const CredentialView = ({ projectName, credential }: CredentialViewProps) => {
  const dispatch = useAppDispatch();

  return (
    <Center>
      <VStack width="90%" align="left">
        <Heading color="teal.500" size="lg" alignSelf="center" mb={4}>
          {credential.id}
        </Heading>
        <Spacer />
        <TableContainer>
          <Table fontSize={'lg'} variant="unstyled">
            <Tbody>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={GrOrganization} /> Project
                </HeadRow>
                <Td fontWeight="semibold">{projectName}</Td>
              </Tr>
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
              {credential.type === 'public_key' && (
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
              {credential.type === 'password' && (
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

              {credential.type === 'access_token' && (
                <Tr>
                  <HeadRow>
                    <AlignedIcon as={GoKey} /> Access Token
                  </HeadRow>
                  <Td>
                    <SecretViewer dispatch={dispatch} secretProvider={() => credential.accessToken} />
                  </Td>
                </Tr>
              )}
              <Tr>
                <HeadRow>
                  <AlignedIcon as={VscRegex} /> Hostname patterns
                </HeadRow>
                <Td>
                  {credential.hostnamePatterns.length === 0 ? (
                    '-'
                  ) : (
                    <Code p={2} variant="outline">
                      {credential.hostnamePatterns.join(', ')}
                    </Code>
                  )}
                </Td>
              </Tr>
            </Tbody>
          </Table>
        </TableContainer>

        <Center mt={10}>
          <Link href={`/app/projects/${projectName}/credentials/${credential.id}/edit`}>
            <Button colorScheme="teal">
              <EditIcon mr={2} />
              Edit credential
            </Button>
          </Link>
        </Center>
      </VStack>
    </Center>
  );
};

export default CredentialView;
