import { Box, Flex, FormControl, FormLabel, HStack, Radio, RadioGroup, Spacer, VStack } from '@chakra-ui/react';
import { RepositoryRole } from 'dogma/features/auth/RepositoryRole';
import { ConfirmUpdateRepositoryProjectRoles } from 'dogma/features/repo/roles/ConfirmUpdateRepositoryProjectRoles';
import { ProjectRolesDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { Controller, useForm } from 'react-hook-form';

const getRole = (role: RepositoryRole | null) => {
  return role || 'NONE';
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
  const {
    handleSubmit,
    control,
    reset,
    formState: { isDirty },
  } = useForm<ProjectRolesDto>({
    defaultValues: projectRoles,
  });
  return (
    <Box>
      <form>
        <VStack spacing={10} mt={6}>
          <FormControl as="fieldset">
            <Box borderWidth="1px" borderRadius="lg" overflow="hidden" p={4}>
              <FormLabel as="legend">Member</FormLabel>
              <Controller
                control={control}
                name="member"
                render={({ field: { onChange, value } }) => (
                  <RadioGroup colorScheme="teal" value={getRole(value)} onChange={onChange}>
                    <HStack spacing={20}>
                      <Radio value="ADMIN">Admin</Radio>
                      <Radio value="WRITE">Write</Radio>
                      <Radio value="READ">Read</Radio>
                      <Radio value="NONE">Forbidden</Radio>
                    </HStack>
                  </RadioGroup>
                )}
              />
            </Box>
          </FormControl>
          <FormControl as="fieldset">
            <Box borderWidth="1px" borderRadius="lg" overflow="hidden" p={4}>
              <FormLabel as="legend">Guest</FormLabel>
              <Controller
                control={control}
                name="guest"
                render={({ field: { onChange, value } }) => (
                  <RadioGroup colorScheme="teal" value={getRole(value)} onChange={onChange}>
                    <HStack spacing={20}>
                      <Radio value="READ">Read</Radio>
                      <Radio value="NONE">Forbidden</Radio>
                    </HStack>
                  </RadioGroup>
                )}
              />
            </Box>
          </FormControl>
        </VStack>
        <Flex gap={4} mt={10}>
          <Spacer />
          <ConfirmUpdateRepositoryProjectRoles
            projectName={projectName}
            repoName={repoName}
            handleSubmit={handleSubmit}
            isDirty={isDirty}
            reset={reset}
          />
        </Flex>
      </form>
    </Box>
  );
};
