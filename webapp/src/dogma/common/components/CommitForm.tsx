/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import { Button, FormControl, Heading, Input, Stack, Textarea, VStack } from '@chakra-ui/react';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { useLazyGetFileContentQuery, usePushFileChangesMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { useForm } from 'react-hook-form';
import { detectChangeType, guessChangeType } from 'dogma/features/file/StructuredFileSupport';
import { FILE_NAME_PATTERN } from 'dogma/util/path-util';

type FormData = {
  summary: string;
  detail: string;
};

export type CommitFormProps = {
  projectName: string;
  repoName: string;
  path: string;
  name: string;
  // The new file name when the file is renamed. The original name is used if not specified.
  newName?: string;
  content: () => string;
  readOnly: boolean;
  setReadOnly: (readOnly: boolean) => void;
  switchMode: () => void;
  handleTabChange: (index: number) => void;
  onRenamed?: (newPath: string) => void;
};

export const CommitForm = ({
  projectName,
  repoName,
  path,
  name,
  newName,
  content,
  readOnly,
  setReadOnly,
  switchMode,
  handleTabChange,
  onRenamed,
}: CommitFormProps) => {
  const [updateFile, { isLoading }] = usePushFileChangesMutation();
  const [getFileContent, { isFetching: isCheckingFile }] = useLazyGetFileContentQuery();
  const { register, handleSubmit, reset } = useForm<FormData>();
  const dispatch = useAppDispatch();
  const onSubmit = async (formData: FormData) => {
    const newContent = content();
    // An empty new name is invalid rather than falling back to the original name.
    const targetName = newName ?? name;
    const renamed = targetName !== name;
    if (renamed && !FILE_NAME_PATTERN.test(targetName)) {
      dispatch(newNotification('Invalid file name.', `'${targetName}' is not a valid file name.`, 'error'));
      return;
    }
    const newPath = renamed ? path.substring(0, path.lastIndexOf('/') + 1) + targetName : path;
    let changeType;
    try {
      changeType = detectChangeType(targetName, newContent);
    } catch (error) {
      dispatch(newNotification(`Invalid file content.`, ErrorMessageParser.parse(error), 'error'));
      return;
    }

    if (renamed) {
      // The server does not reject renaming a file to the path of an existing directory,
      // which replaces the whole directory with the file. Check the new path beforehand.
      try {
        await getFileContent({ projectName, repoName, filePath: newPath, revision: 'head' }).unwrap();
        dispatch(newNotification(`Failed to update ${path}`, `${newPath} already exists.`, 'error'));
        return;
      } catch (error) {
        if ((error as FetchBaseQueryError).status !== 404) {
          dispatch(newNotification(`Failed to update ${path}`, ErrorMessageParser.parse(error), 'error'));
          return;
        }
      }
    }

    const changes = [];
    if (renamed) {
      const oldChangeType = guessChangeType(name);
      if (oldChangeType !== changeType && changeType !== 'UPSERT_TEXT' && oldChangeType !== 'UPSERT_JSON') {
        // The server validates the old content against the new entry type when renaming.
        // Update the old file with the new content first so that the validation passes.
        // e.g. foo.txt -> foo.json, foo.yaml -> foo.json
        // A JSON file does not need it because JSON is also valid YAML.
        changes.push({ path, type: oldChangeType, rawContent: newContent });
      }
      // RENAME also fails if a file already exists at the new path, which prevents overwriting it.
      changes.push({ path, type: 'RENAME', content: newPath });
    }
    changes.push({ path: newPath, type: changeType, rawContent: newContent });

    const data = {
      commitMessage: {
        summary: formData.summary,
        detail: formData.detail,
      },
      changes,
    };
    try {
      const response = await updateFile({ projectName, repoName, data }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      if (renamed) {
        dispatch(newNotification('File renamed', `Successfully renamed ${path} to ${newPath}`, 'success'));
      } else {
        dispatch(newNotification('File updated', `Successfully updated ${path}`, 'success'));
      }
      setReadOnly(true);
      reset();
      handleTabChange(0);
      if (renamed) {
        onRenamed?.(newPath);
      }
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
          <Button
            type="submit"
            colorScheme="teal"
            isLoading={isLoading || isCheckingFile}
            loadingText="Creating"
          >
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
