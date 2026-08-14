import {
  Badge,
  Box,
  Flex,
  FormControl,
  FormLabel,
  HStack,
  Radio,
  RadioGroup,
  Spacer,
  Stack,
  Text,
  Tooltip,
  VStack,
} from '@chakra-ui/react';
import { RepositoryRole } from 'dogma/features/auth/RepositoryRole';
import {
  ConfirmUpdateRepositoryProjectRoles,
  VisibilityChange,
} from 'dogma/features/repo/roles/ConfirmUpdateRepositoryProjectRoles';
import { ProjectRolesDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { Controller, useForm } from 'react-hook-form';

const getRole = (role: RepositoryRole | null) => {
  return role || 'NONE';
};

export const ProjectRolesForm = ({
  projectName,
  repoName,
  projectRoles,
  allowPublicRepositories,
}: {
  projectName: string;
  repoName: string;
  projectRoles: ProjectRolesDto;
  allowPublicRepositories: boolean;
}) => {
  const {
    handleSubmit,
    control,
    reset,
    watch,
    formState: { isDirty },
  } = useForm<ProjectRolesDto>({
    values: projectRoles,
    resetOptions: { keepDirtyValues: true },
  });
  const isPublic = projectRoles?.guest === 'READ';
  const selectedPublic = watch('guest') === 'READ';
  let visibilityChange: VisibilityChange = null;
  if (selectedPublic !== isPublic) {
    visibilityChange = selectedPublic ? 'toPublic' : 'toPrivate';
  }
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
              <FormLabel as="legend">
                Visibility{' '}
                <Badge
                  fontSize="x-small"
                  colorScheme={isPublic ? 'teal' : 'gray'}
                  variant="outline"
                  borderRadius="full"
                  px={2}
                >
                  {isPublic ? 'Public' : 'Private'}
                </Badge>
              </FormLabel>
              <Controller
                control={control}
                name="guest"
                render={({ field: { onChange, value } }) => (
                  <RadioGroup colorScheme="teal" value={getRole(value)} onChange={onChange}>
                    <Stack spacing={4}>
                      <Radio value="NONE">
                        <Text fontWeight="semibold">Private</Text>
                        <Text fontSize="sm" color="gray.500">
                          Only members and users or app identities granted a role can access this repository.
                        </Text>
                      </Radio>
                      <Tooltip
                        label="The project settings do not allow public repositories."
                        isDisabled={allowPublicRepositories || isPublic}
                      >
                        <Box>
                          <Radio value="READ" isDisabled={!allowPublicRepositories && !isPublic}>
                            <Text fontWeight="semibold">Public</Text>
                            <Text fontSize="sm" color="gray.500">
                              Everyone who can sign in — and all application tokens and certificates with guest
                              access allowed — can read this repository. Write access is never granted to
                              guests.
                            </Text>
                          </Radio>
                        </Box>
                      </Tooltip>
                    </Stack>
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
            visibilityChange={visibilityChange}
          />
        </Flex>
      </form>
    </Box>
  );
};
