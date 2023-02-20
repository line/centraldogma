import { Box, Flex, FormControl, FormLabel, HStack, Radio, RadioGroup, Spacer, VStack } from '@chakra-ui/react';
import { ConfirmUpdateRolePermission } from 'dogma/features/repo/permissions/ConfirmUpdateRolePermission';
import { RepoRolePermissionDto } from 'dogma/features/repo/RepoPermissionDto';
import { useState } from 'react';
import { MetadataButton } from 'dogma/common/components/MetadataButton';

const getPermission = (permissions: Array<'READ' | 'WRITE'>) => {
  return permissions.find((permission) => permission === 'WRITE')
    ? 'write'
    : permissions.find((permission) => permission === 'READ')
    ? 'read'
    : 'none';
};
const constructPermissions = (permission: string): Array<'READ' | 'WRITE'> =>
  permission === 'write' ? ['READ', 'WRITE'] : permission === 'read' ? ['READ'] : [];

export const RolePermissionForm = ({
  projectName,
  repoName,
  perRolePermissions,
}: {
  projectName: string;
  repoName: string;
  perRolePermissions: RepoRolePermissionDto;
}) => {
  const [member, setMember] = useState(getPermission(perRolePermissions.member));
  const [guest, setGuest] = useState(getPermission(perRolePermissions.guest));
  return (
    <Box>
      <VStack spacing={10} mt={6}>
        <FormControl as="fieldset">
          <Box borderWidth="1px" borderRadius="lg" overflow="hidden" p={4}>
            <FormLabel as="legend">Owner</FormLabel>
            <RadioGroup colorScheme="teal" value="write">
              <Radio value="write" disabled>
                Read Write
              </Radio>
            </RadioGroup>
          </Box>
        </FormControl>
        <FormControl as="fieldset">
          <Box borderWidth="1px" borderRadius="lg" overflow="hidden" p={4}>
            <FormLabel as="legend">Member</FormLabel>
            <RadioGroup colorScheme="teal" value={member} onChange={setMember}>
              <HStack spacing={20}>
                <Radio value="read">Read Only</Radio>
                <Radio value="write">Read Write</Radio>
              </HStack>
            </RadioGroup>
          </Box>
        </FormControl>
        <FormControl as="fieldset">
          <Box borderWidth="1px" borderRadius="lg" overflow="hidden" p={4}>
            <FormLabel as="legend">Guest</FormLabel>
            <RadioGroup colorScheme="teal" value={guest} onChange={setGuest}>
              <HStack spacing={20}>
                <Radio value="read">Read Only</Radio>
                <Radio value="write">Read Write</Radio>
              </HStack>
            </RadioGroup>
          </Box>
        </FormControl>
      </VStack>
      <Flex gap={4} mt={10}>
        <Spacer />
        <MetadataButton href={`/app/projects/metadata/${projectName}`} text="Project Metadata" />
        <ConfirmUpdateRolePermission
          projectName={projectName}
          repoName={repoName}
          data={{
            owner: constructPermissions('write'),
            member: constructPermissions(member),
            guest: constructPermissions(guest),
          }}
        />
      </Flex>
    </Box>
  );
};
