import { Box, Flex, FormControl, FormLabel, Spacer, Switch, Text } from '@chakra-ui/react';
import { useUpdateAllowPublicRepositoriesMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { WithProjectRole } from 'dogma/features/auth/ProjectRole';

export const AllowPublicRepositoriesToggle = ({
  projectName,
  allowed,
}: {
  projectName: string;
  allowed: boolean;
}) => {
  const dispatch = useAppDispatch();
  const [updateAllowPublicRepositories, { isLoading }] = useUpdateAllowPublicRepositoriesMutation();

  const onChange = async (allow: boolean) => {
    try {
      await updateAllowPublicRepositories({ projectName, allow }).unwrap();
      dispatch(
        newNotification(
          allow ? 'Public repositories allowed' : 'Public repositories disallowed',
          `Successfully updated ${projectName}`,
          'success',
        ),
      );
    } catch (error) {
      dispatch(
        newNotification('Failed to update the project setting', ErrorMessageParser.parse(error), 'error'),
      );
    }
  };

  return (
    <WithProjectRole projectName={projectName} roles={['OWNER']}>
      {() => (
        <Box borderWidth="1px" borderRadius="lg" p={4} mb={4}>
          <FormControl display="flex" alignItems="center">
            <Flex direction="column" gap={1}>
              <FormLabel htmlFor="allow-public-repositories" mb={0} fontWeight="semibold">
                Allow public repositories
              </FormLabel>
              <Text fontSize="sm" color="gray.500">
                When disabled, repositories in this project cannot be made public. Existing public repositories
                must be switched to private first.
              </Text>
            </Flex>
            <Spacer />
            <Switch
              id="allow-public-repositories"
              colorScheme="teal"
              isChecked={allowed}
              isDisabled={isLoading}
              onChange={(e) => onChange(e.target.checked)}
            />
          </FormControl>
        </Box>
      )}
    </WithProjectRole>
  );
};
