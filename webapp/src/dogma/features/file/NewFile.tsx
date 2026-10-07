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
/* eslint-disable react/no-children-prop */
import {
  Box,
  Button,
  Divider,
  Flex,
  FormControl,
  FormErrorMessage,
  FormLabel,
  Heading,
  Input,
  InputGroup,
  InputLeftAddon,
  Radio,
  RadioGroup,
  Spacer,
  Stack,
  Textarea,
  useColorMode,
  useDisclosure,
  VStack,
} from '@chakra-ui/react';
import { useLazyGetFileContentQuery, usePushFileChangesMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import { useAppDispatch } from 'dogma/hooks';
import Router from 'next/router';
import { useForm } from 'react-hook-form';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import Editor, { OnMount } from '@monaco-editor/react';
import { ChangeEvent, KeyboardEvent, useRef, useState } from 'react';
import { extensionToLanguageMap } from 'dogma/common/components/editor/FileEditor';
import { registerJson5Language } from 'dogma/features/file/Json5Language';
import { detectChangeType } from 'dogma/features/file/StructuredFileSupport';
import { useLocalMonaco } from 'dogma/features/file/MonacoLoader';
import { Loading } from 'dogma/common/components/Loading';
import { OverwriteFileModal } from 'dogma/features/file/OverwriteFileModal';

const FILE_PATH_PATTERN = /^[-_.0-9a-zA-Z]*[-_0-9a-zA-Z]+$/;

type PushData = {
  commitMessage: {
    summary: string;
    detail: string;
    markup: string;
  };
  changes: { path: string; type: string; rawContent: string }[];
};

type FormData = {
  name: string;
  summary: string;
  detail: string;
};

export const NewFile = ({
  projectName,
  repoName,
  initialPrefixes,
}: {
  projectName: string;
  repoName: string;
  initialPrefixes: string[];
}) => {
  const { colorMode } = useColorMode();
  const [addNewFle, { isLoading }] = usePushFileChangesMutation();
  const [getFileContent, { isFetching: isCheckingFile }] = useLazyGetFileContentQuery();
  const {
    isOpen: isOverwriteModalOpen,
    onOpen: onOverwriteModalOpen,
    onClose: onOverwriteModalClose,
  } = useDisclosure();
  const [pendingData, setPendingData] = useState<PushData>(null);
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>();
  const dispatch = useAppDispatch();
  const [prefixes] = useState(initialPrefixes);
  const pushNewFile = async (data: PushData) => {
    try {
      const response = await addNewFle({ projectName, repoName, data }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      Router.push(`/app/projects/${projectName}/repos/${repoName}/tree/head/${prefixes.join('/')}`);
      reset();
      dispatch(newNotification('New file created', `Successfully created ${data.changes[0].path}`, 'success'));
    } catch (error) {
      dispatch(newNotification('Failed to create a new file', ErrorMessageParser.parse(error), 'error'));
    }
  };
  const onSubmit = async (formData: FormData) => {
    const path = `/${[...prefixes, formData.name].join('/')}`;
    const content = editorRef.current.getValue();
    let changeType;
    try {
      changeType = detectChangeType(fileName, content);
    } catch (error) {
      dispatch(newNotification(`Invalid file content.`, ErrorMessageParser.parse(error), 'error'));
      return;
    }

    const data = {
      commitMessage: {
        summary: formData.summary,
        detail: formData.detail,
        markup: markup,
      },
      changes: [
        {
          path: path,
          type: changeType,
          rawContent: content,
        },
      ],
    };

    // UPSERT silently overwrites an existing file, so ask the user before overwriting it.
    try {
      const existing = await getFileContent({
        projectName,
        repoName,
        filePath: path,
        revision: 'head',
      }).unwrap();
      if (!existing) {
        // A directory at the path returns no content. Pushing a file there replaces the whole directory.
        dispatch(
          newNotification('Failed to create a new file', `A directory already exists at ${path}`, 'error'),
        );
        return;
      }
      setPendingData(data);
      onOverwriteModalOpen();
      return;
    } catch (error) {
      if ((error as FetchBaseQueryError).status !== 404) {
        dispatch(newNotification('Failed to create a new file', ErrorMessageParser.parse(error), 'error'));
        return;
      }
    }
    await pushNewFile(data);
  };
  const [markup, setMarkup] = useState('PLAINTEXT');
  const editorRef = useRef(null);
  const handleEditorMount: OnMount = (editor) => {
    editorRef.current = editor;
  };
  const [fileName, setFileName] = useState('');
  const handleFileNameInput = (e: ChangeEvent<HTMLInputElement>) => {
    setFileName(e.target.value);
  };
  const handleShortcut = (e: KeyboardEvent) => {
    if (e.key === '/') {
      e.preventDefault();
      if (fileName) {
        prefixes.push(fileName);
      }
      setFileName('');
    } else if (e.key === 'Backspace' && !fileName.length && prefixes.length) {
      e.preventDefault();
      setFileName(prefixes.pop());
    }
  };
  let language;
  if (!fileName) {
    language = 'json';
  } else {
    const fileExtension = fileName.substring(fileName.lastIndexOf('.') + 1);
    language = extensionToLanguageMap[fileExtension] || fileExtension;
  }

  const monaco = useLocalMonaco();
  if (!monaco) {
    return <Loading />;
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)}>
      <Flex minWidth="max-content" alignItems="center" mb={4}>
        <Heading size="lg">Create new file</Heading>
      </Flex>
      <Box p={4}>
        <VStack>
          <FormControl isInvalid={errors.name ? true : false} isRequired>
            <FormLabel>Path</FormLabel>
            <InputGroup>
              <InputLeftAddon children={`/${prefixes.join('/')}`} />
              <Input
                type="text"
                value={fileName}
                placeholder="Type 1) a file name 2) a directory name and '/' key or 3) 'backspace' key to go one directory up."
                {...register('name', { pattern: FILE_PATH_PATTERN })}
                onChange={handleFileNameInput}
                onKeyDown={handleShortcut}
              />
            </InputGroup>
            {errors.name && <FormErrorMessage>Invalid file name</FormErrorMessage>}
          </FormControl>
          <FormControl>
            <FormLabel>Content</FormLabel>
            <Editor
              height="40vh"
              language={language}
              theme={colorMode === 'light' ? 'light' : 'vs-dark'}
              options={{
                autoIndent: 'full',
                formatOnPaste: true,
                formatOnType: true,
                automaticLayout: true,
                scrollBeyondLastLine: false,
              }}
              onMount={handleEditorMount}
              beforeMount={registerJson5Language}
            />
          </FormControl>
        </VStack>
        <Divider />
        <VStack p={4} gap="2" mb={6} align="stretch">
          <Flex>
            <Heading size="md">Commit changes</Heading>
            <Spacer />
            <RadioGroup onChange={setMarkup} value={markup} mb={2}>
              <Flex gap={2}>
                <Radio value="PLAINTEXT" colorScheme="teal">
                  Plain text
                </Radio>
                <Radio value="MARKDOWN" colorScheme="teal">
                  Markdown
                </Radio>
              </Flex>
            </RadioGroup>
          </Flex>
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
            <Button variant="outline" onClick={() => Router.back()}>
              Cancel
            </Button>
          </Stack>
        </VStack>
      </Box>
      <OverwriteFileModal
        isOpen={isOverwriteModalOpen}
        onClose={onOverwriteModalClose}
        path={pendingData?.changes[0].path}
        isLoading={isLoading}
        onOverwrite={async () => {
          await pushNewFile(pendingData);
          onOverwriteModalClose();
        }}
      />
    </form>
  );
};
