import { Box, Flex, FormControl, FormLabel, HStack, Radio, RadioGroup, Spacer, VStack } from '@chakra-ui/react';
import { ConfirmUpdateRolePermission } from 'dogma/features/repo/permissions/ConfirmUpdateRolePermission';
import { RepoRolePermissionDto } from 'dogma/features/repo/RepoPermissionDto';
import { useState } from 'react';
import { MetadataButton } from 'dogma/common/components/MetadataButton';

const getPermission = (permission: 'READ' | 'WRITE' | 'REPO_ADMIN' | null) => {
  return permission == null ? 'NONE' : permission;
};

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
            <FormLabel as="legend">Member</FormLabel>
            <RadioGroup colorScheme="teal" value={member} onChange={setMember}>
              <HStack spacing={20}>
                <Radio value="REPO_ADMIN">Admin</Radio>
                <Radio value="WRITE">Write</Radio>
                <Radio value="READ">Read</Radio>
                <Radio value="NONE">Forbidden</Radio>
              </HStack>
            </RadioGroup>
          </Box>
        </FormControl>
        <FormControl as="fieldset">
          <Box borderWidth="1px" borderRadius="lg" overflow="hidden" p={4}>
            <FormLabel as="legend">Guest</FormLabel>
            <RadioGroup colorScheme="teal" value={guest} onChange={setGuest}>
              <HStack spacing={20}>
                <Radio value="WRITE">Write</Radio>
                <Radio value="READ">Read</Radio>
                <Radio value="NONE">Forbidden</Radio>
              </HStack>
            </RadioGroup>
          </Box>
        </FormControl>
      </VStack>
      <Flex gap={4} mt={10}>
        <Spacer />
        <MetadataButton href={`/app/projects/${projectName}/settings`} text="Project Settings" />
        <ConfirmUpdateRolePermission
          projectName={projectName}
          repoName={repoName}
          data={{
            member: member === 'NONE' ? null : (member as 'READ' | 'WRITE' | 'REPO_ADMIN'),
            guest: guest === 'NONE' ? null : (guest as 'READ' | 'WRITE'),
          }}
        />
      </Flex>
    </Box>
  );
};
