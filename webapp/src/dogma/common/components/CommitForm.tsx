import { Button, FormControl, Heading, Input, Stack, Textarea, VStack } from '@chakra-ui/react';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { usePushFileChangesMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { useForm } from 'react-hook-form';

type FormData = {
  summary: string;
  detail: string;
};

export type CommitFormProps = {
  projectName: string;
  repoName: string;
  path: string;
  name: string;
  content: () => string;
  readOnly: boolean;
  setReadOnly: (readOnly: boolean) => void;
  switchMode: () => void;
  handleTabChange: (index: number) => void;
};

export const CommitForm = ({
  projectName,
  repoName,
  path,
  name,
  content,
  readOnly,
  setReadOnly,
  switchMode,
  handleTabChange,
}: CommitFormProps) => {
  const [updateFile, { isLoading }] = usePushFileChangesMutation();
  const { register, handleSubmit, reset } = useForm<FormData>();
  const dispatch = useAppDispatch();
  const onSubmit = async (formData: FormData) => {
    let newContent = content();
    if (name.endsWith('.json')) {
      try {
        newContent = JSON.parse(newContent);
      } catch (error) {
        dispatch(newNotification(`Failed to format json content.`, ErrorMessageParser.parse(error), 'error'));
        return;
      }
    }

    const data = {
      commitMessage: {
        summary: formData.summary,
        detail: formData.detail,
      },
      changes: [
        {
          path: path,
          type: name.endsWith('.json') ? 'UPSERT_JSON' : 'UPSERT_TEXT',
          content: newContent,
        },
      ],
    };
    try {
      const response = await updateFile({ projectName, repoName, data }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification('File updated', `Successfully updated ${path}`, 'success'));
      setReadOnly(true);
      reset();
      handleTabChange(0);
    } catch (error) {
      dispatch(newNotification(`Failed to update ${path}`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <form onSubmit={handleSubmit(onSubmit)}>
      <VStack p={4} gap="2" mb={6} align="stretch" display={readOnly ? 'none' : 'visible'}>
        <Heading size="md">Commit changes</Heading>
        <FormControl isRequired>
          <Input
            id="summary"
            name="summary"
            type="text"
            placeholder="Add a summary"
            {...register('summary', { required: true })}
          />
        </FormControl>
        <Textarea
          id="description"
          name="description"
          placeholder="Add an optional extended description..."
          {...register('detail')}
        />
        <Stack direction="row" spacing={4} mt={2}>
          <Button type="submit" colorScheme="teal" isLoading={isLoading} loadingText="Creating">
            Commit
          </Button>
          <Button variant="outline" onClick={switchMode}>
            Cancel
          </Button>
        </Stack>
      </VStack>
    </form>
  );
};
