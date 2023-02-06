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
  Radio,
  RadioGroup,
  Select,
  Spacer,
  Stack,
  Textarea,
  VStack,
  useColorMode,
} from '@chakra-ui/react';
import { useAddNewFileMutation } from 'dogma/features/api/apiSlice';
import { createMessage } from 'dogma/features/message/messageSlice';
import { useAppDispatch } from 'dogma/store';
import Router from 'next/router';
import { useForm } from 'react-hook-form';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/dist/query';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import Editor, { OnMount } from '@monaco-editor/react';
import { useRef, useState } from 'react';

const FILE_PATH_PATTERN = /^[0-9A-Za-z](?:[-+_0-9A-Za-z\.]*[0-9A-Za-z])?$/;

type FormData = {
  name: string;
  type: 'UPSERT_JSON' | 'UPSERT_TEXT';
  summary: string;
  detail: string;
};

export const NewFile = ({
  projectName,
  repoName,
}: {
  projectName: string;
  repoName: string;
  revision: string;
}) => {
  const { colorMode } = useColorMode();
  const [addNewFle, { isLoading }] = useAddNewFileMutation();
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>();
  const dispatch = useAppDispatch();
  const onSubmit = async (formData: FormData) => {
    const data = {
      commitMessage: {
        summary: formData.summary,
        detail: formData.detail,
        markup: markup,
      },
      changes: [
        {
          path: '/' + formData.name, // TODO: Allow the actual path in the input form i.e. allow slash /
          type: formData.type,
          content: editorRef.current.getValue(),
        },
      ],
    };
    try {
      const response = await addNewFle({ projectName, repoName, data }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      Router.push(`/app/projects/${projectName}/repos/${repoName}/list/head/`);
      reset();
      dispatch(
        createMessage({
          title: 'New file created',
          text: `Successfully created ${formData.name}`,
          type: 'success',
        }),
      );
    } catch (error) {
      dispatch(
        createMessage({
          title: 'Failed to create a new file',
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
    }
  };
  const [markup, setMarkup] = useState('PLAINTEXT');
  const editorRef = useRef(null);
  const handleEditorMount: OnMount = (editor) => {
    editorRef.current = editor;
  };
  return (
    <form onSubmit={handleSubmit(onSubmit)}>
      <Flex minWidth="max-content" alignItems="center" mb={4}>
        <Heading size="lg">Create new file</Heading>
      </Flex>
      <Box p={4}>
        <RadioGroup onChange={setMarkup} value={markup} mb={2}>
          <Flex gap={2}>
            <Spacer />
            <Radio value="PLAINTEXT" colorScheme="teal">
              plain text
            </Radio>
            <Radio value="MARKDOWN" colorScheme="teal">
              markdown
            </Radio>
          </Flex>
        </RadioGroup>
        <VStack>
          <FormControl isInvalid={errors.name ? true : false} isRequired>
            <FormLabel>Path</FormLabel>
            {/* TODO: Allow slash / in the file path/name, i.e.exclude this input from the document hotkey */}
            <Input
              type="text"
              placeholder="my-file-name"
              {...register('name', { pattern: FILE_PATH_PATTERN })}
            />
            {errors.name && <FormErrorMessage>Invalid file name</FormErrorMessage>}
          </FormControl>
          <FormControl>
            <FormLabel>Type</FormLabel>
            <Select {...register('type')} defaultValue="UPSERT_JSON" name="type">
              <option key="UPSERT_JSON" value="UPSERT_JSON">
                JSON
              </option>
              <option key="UPSERT_TEXT" value="UPSERT_TEXT">
                TEXT
              </option>
            </Select>
          </FormControl>
          <FormControl>
            <FormLabel>Content</FormLabel>
            <Editor
              height="40vh"
              defaultLanguage="json"
              theme={colorMode === 'light' ? 'light' : 'vs-dark'}
              options={{
                autoIndent: 'full',
                formatOnPaste: true,
                formatOnType: true,
                automaticLayout: true,
                scrollBeyondLastLine: false,
              }}
              onMount={handleEditorMount}
            />
          </FormControl>
        </VStack>
        <Divider />
        <VStack p={4} gap="2" mb={6} align="stretch">
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
            <Button variant="outline" onClick={() => Router.back()}>
              Cancel
            </Button>
          </Stack>
        </VStack>
      </Box>
    </form>
  );
};
