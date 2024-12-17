import { Box, Flex, FormControl, FormLabel, HStack, Radio, RadioGroup, Spacer, VStack } from '@chakra-ui/react';
import { ConfirmUpdateRepositoryProjectRoles } from 'dogma/features/repo/roles/ConfirmUpdateRepositoryProjectRoles';
import { ProjectRolesDto, RepositoryRole } from 'dogma/features/repo/RepositoriesMetadataDto';
import { useState } from 'react';
import { MetadataButton } from 'dogma/common/components/MetadataButton';

const getRole = (role: RepositoryRole | null) => {
  return role == null ? 'NONE' : role;
};

export const ProjectRolesForm = ({
  projectName,
  repoName,
  projectRoles,
}: {
  projectName: string;
  repoName: string;
  projectRoles: ProjectRolesDto;
}) => {
  const [member, setMember] = useState(getRole(projectRoles.member));
  const [guest, setGuest] = useState(getRole(projectRoles.guest));
  return (
    <Box>
      <VStack spacing={10} mt={6}>
        <FormControl as="fieldset">
          <Box borderWidth="1px" borderRadius="lg" overflow="hidden" p={4}>
            <FormLabel as="legend">Member</FormLabel>
            <RadioGroup colorScheme="teal" value={member} onChange={setMember}>
              <HStack spacing={20}>
                <Radio value="ADMIN">Admin</Radio>
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
        <ConfirmUpdateRepositoryProjectRoles
          projectName={projectName}
          repoName={repoName}
          data={{
            member: member === 'NONE' ? null : (member as RepositoryRole),
            guest: guest === 'NONE' ? null : (guest as 'READ' | 'WRITE'),
          }}
        />
      </Flex>
    </Box>
  );
};
