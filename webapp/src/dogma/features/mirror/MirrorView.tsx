import {
  Badge,
  Button,
  Center,
  Code,
  Heading,
  Icon,
  Link,
  Table,
  TableContainer,
  Tbody,
  Td,
  Text,
  Tr,
  VStack,
} from '@chakra-ui/react';
import { GiMirrorMirror, GiPowerButton } from 'react-icons/gi';
import { BiTimer } from 'react-icons/bi';
import { GoKey, GoMirror, GoRepo} from 'react-icons/go';
import { IoBanSharp } from 'react-icons/io5';
import { EditIcon } from '@chakra-ui/icons';
import React, { ReactNode } from 'react';
import { CredentialDto } from 'dogma/features/credential/CredentialDto';
import { MirrorDto } from 'dogma/features/mirror/MirrorDto';
import { IconType } from 'react-icons';
import {GrOrganization} from "react-icons/gr";
import {VscRepoClone} from "react-icons/vsc";

const HeadRow = ({ children }: { children: ReactNode }) => (
  <Td width="250px" fontWeight="semibold">
    {children}
  </Td>
);

const AlignedIcon = ({ as }: { as: IconType }) => <Icon as={as} marginBottom="-4px" marginRight={2} />;

interface MirrorViewProps {
  projectName: string;
  mirror: MirrorDto;
  credential: CredentialDto;
}

const MirrorView = ({ projectName, mirror, credential }: MirrorViewProps) => {
  return (
    <Center>
      <VStack width="90%" align="left">
        <Heading color="teal.500" size="lg" alignSelf="center" mb={4}>
          {mirror.id}
        </Heading>
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
                  <AlignedIcon as={GiMirrorMirror} /> Mirror ID
                </HeadRow>
                <Td fontWeight="semibold">{mirror.id}</Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={BiTimer} /> Schedule
                </HeadRow>
                <Td>
                  <Code variant="outline" p={1}>
                    {mirror.schedule}
                  </Code>
                </Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={GoMirror} /> Direction
                </HeadRow>
                <Td>
                  <Badge colorScheme={'blue'}>{mirror.direction}</Badge>
                </Td>
              </Tr>

              <Tr>
                <HeadRow>
                  <AlignedIcon as={GoRepo} /> Local path
                </HeadRow>
                <Td>
                  <Link
                    href={`/app/projects/${projectName}/repos/${mirror.localRepo}/list/head${mirror.localPath}`}
                  >
                    <Code fontSize="md" padding="2px 10px 2px 10px">
                      dogma://{projectName}/{mirror.localRepo}
                      {mirror.localPath}
                    </Code>
                  </Link>
                </Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={VscRepoClone} /> Remote path
                </HeadRow>
                <Td>
                  <Code fontSize="md" padding="2px 10px 2px 10px">
                    {mirror.remoteScheme}://{mirror.remoteUrl}
                    {mirror.remotePath}#{mirror.remoteBranch}
                  </Code>
                </Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={GoKey} /> Credential
                </HeadRow>
                <Td>
                  {credential && (
                    <Link href={`/app/projects/${projectName}/credentials/${credential.id}`}>
                      {mirror.credentialId}
                    </Link>
                  )}
                </Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={IoBanSharp} /> gitignore
                </HeadRow>
                <Td>
                  <Text>{mirror.gitignore}</Text>
                </Td>
              </Tr>
              <Tr>
                <HeadRow>
                  <AlignedIcon as={GiPowerButton} /> Status
                </HeadRow>
                <Td>
                  {mirror.enabled ? (
                    <Badge colorScheme={'green'}>Enabled</Badge>
                  ) : (
                    <Badge colorScheme={'red'}>Disabled</Badge>
                  )}
                </Td>
              </Tr>
            </Tbody>
          </Table>
        </TableContainer>

        <Center mt={10}>
          <Link href={`/app/projects/${projectName}/mirrors/${mirror.id}/edit`}>
            <Button colorScheme="teal">
              <EditIcon mr={2} />
              Edit mirror
            </Button>
          </Link>
        </Center>
      </VStack>
    </Center>
  );
};

export default MirrorView;
