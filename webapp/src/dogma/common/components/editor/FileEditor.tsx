import {
  Box,
  Button,
  Divider,
  Flex,
  FormControl,
  Heading,
  Input,
  Spacer,
  Stack,
  Tab,
  TabList,
  TabPanel,
  TabPanels,
  Tabs,
  Textarea,
  VStack,
  useColorMode,
  useDisclosure,
} from '@chakra-ui/react';
import Editor, { DiffEditor, OnMount } from '@monaco-editor/react';
import { EditModeToggle } from 'dogma/common/components/editor/EditModeToggle';
import React, { useState, useRef } from 'react';
import { FcEditImage, FcCancel } from 'react-icons/fc';
import { JsonPath } from 'dogma/common/components/editor/JsonPath';
import { JsonPathLegend } from 'dogma/common/components/JsonPathLegend';
import { useForm } from 'react-hook-form';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/dist/query';
import { usePushFileChangesMutation } from 'dogma/features/api/apiSlice';
import { createMessage } from 'dogma/features/message/messageSlice';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { useAppDispatch } from 'dogma/store';
import { AiOutlineDelete } from 'react-icons/ai';
import { DiscardChangesModal } from 'dogma/common/components/editor/DiscardChangesModal';
import { DeleteFileModal } from 'dogma/common/components/editor/DeleteFileModal';

export type FileEditorProps = {
  projectName: string;
  repoName: string;
  language: string;
  originalContent: string;
  path: string;
  name: string;
};

type FormData = {
  summary: string;
  detail: string;
};

const FileEditor = ({ projectName, repoName, language, originalContent, path, name }: FileEditorProps) => {
  const jsonContent = language === 'json' ? JSON.parse(originalContent) : '';
  const [tabIndex, setTabIndex] = useState(0);
  const handleTabChange = (index: number) => {
    setTabIndex(index);
  };
  const [fileContent, setFileContent] = useState('');
  const editorRef = useRef(null);
  const handleEditorMount: OnMount = (editor) => {
    editorRef.current = editor;
    setFileContent(jsonContent ? JSON.stringify(jsonContent, null, 2) : originalContent);
  };
  const { isOpen: isCancelModalOpen, onOpen: onCancelModalOpen, onClose: onCancelModalClose } = useDisclosure();
  const { isOpen: isDeleteModalOpen, onOpen: onDeleteModalOpen, onClose: onDeleteModalClose } = useDisclosure();
  const [readOnly, setReadOnly] = useState(true);
  const switchMode = () => {
    if (readOnly) {
      setFileContent(jsonContent ? JSON.stringify(jsonContent, null, 2) : originalContent);
      setReadOnly(false);
    } else {
      onCancelModalOpen();
    }
  };
  const resetViewEditor = () => {
    editorRef.current.setValue(fileContent);
    setReadOnly(true);
    setTabIndex(0);
    onCancelModalClose();
  };
  const { colorMode } = useColorMode();
  const [diffSideBySide, setDiffSideBySide] = useState(false);
  const [editorExpanded, setEditorExpanded] = useState(false);
  const [updateFile, { isLoading }] = usePushFileChangesMutation();
  const { register, handleSubmit, reset } = useForm<FormData>();
  const dispatch = useAppDispatch();
  const onSubmit = async (formData: FormData) => {
    const data = {
      commitMessage: {
        summary: formData.summary,
        detail: formData.detail,
      },
      changes: [
        {
          path: path,
          type: name.endsWith('.json') ? 'UPSERT_JSON' : 'UPSERT_TEXT',
          content: editorRef.current.getValue(),
        },
      ],
    };
    try {
      const response = await updateFile({ projectName, repoName, data }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(
        createMessage({
          title: 'File updated',
          text: `Successfully updated ${path}`,
          type: 'success',
        }),
      );
      setReadOnly(true);
      reset();
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to update ${path}`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
    }
  };
  return (
    <Box>
      <Flex gap={4}>
        <Spacer />
        <Button
          onClick={switchMode}
          leftIcon={readOnly ? <FcEditImage /> : <FcCancel />}
          colorScheme={readOnly ? 'teal' : 'blue'}
          variant="outline"
          size="sm"
        >
          {readOnly ? 'Edit' : 'Cancel changes'}
        </Button>
        <Button
          onClick={onDeleteModalOpen}
          leftIcon={<AiOutlineDelete />}
          colorScheme="red"
          display={readOnly ? 'visible' : 'none'}
          size="sm"
        >
          Delete
        </Button>
      </Flex>
      <Tabs
        variant="enclosed-colored"
        size="lg"
        index={tabIndex}
        onChange={handleTabChange}
        colorScheme={readOnly ? 'blue' : 'yellow'}
      >
        <TabList>
          <Tab>
            <Heading size="sm">{readOnly ? 'View file' : 'Edit file'}</Heading>
          </Tab>
          <Tab display={readOnly ? 'none' : 'visible'}>
            <Heading size="sm">Preview Changes</Heading>
          </Tab>
        </TabList>
        <TabPanels>
          <TabPanel>
            <Box>
              <Flex gap={4}>
                <Flex mb={2}>
                  <Spacer />
                  <EditModeToggle
                    switchMode={() => setEditorExpanded(!editorExpanded)}
                    value={!editorExpanded}
                    label="Expand File"
                  />
                </Flex>
                <Spacer />
                {readOnly && language === 'json' ? <JsonPathLegend /> : ''}
              </Flex>
              {readOnly && language === 'json' ? (
                <JsonPath setFileContent={setFileContent} jsonContent={jsonContent} />
              ) : (
                ''
              )}
              <Editor
                height={
                  editorExpanded ? Math.max(editorRef.current.getModel().getLineCount() * 20, 1000) : '50vh'
                }
                language={language}
                theme={colorMode === 'light' ? 'light' : 'vs-dark'}
                value={fileContent}
                options={{
                  autoIndent: 'full',
                  formatOnPaste: true,
                  formatOnType: true,
                  readOnly: readOnly,
                  automaticLayout: true,
                  scrollBeyondLastLine: false,
                }}
                onMount={handleEditorMount}
              />
            </Box>
          </TabPanel>
          <TabPanel display={readOnly ? 'none' : 'visible'}>
            <Flex mb="2">
              <Spacer />
              <EditModeToggle
                switchMode={() => setDiffSideBySide(!diffSideBySide)}
                value={!diffSideBySide}
                label="Render side by side?"
              />
            </Flex>
            <DiffEditor
              height="50vh"
              language={language}
              theme={colorMode === 'light' ? 'light' : 'vs-dark'}
              original={fileContent}
              modified={editorRef?.current?.getValue()}
              options={{
                autoIndent: 'full',
                formatOnPaste: true,
                formatOnType: true,
                diffWordWrap: 'on',
                useTabStops: true,
                renderSideBySide: diffSideBySide,
                scrollBeyondLastLine: false,
              }}
            />
          </TabPanel>
        </TabPanels>
      </Tabs>
      <Divider />
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
      <DiscardChangesModal
        isOpen={isCancelModalOpen}
        onClose={onCancelModalClose}
        resetViewEditor={resetViewEditor}
      />
      <DeleteFileModal
        isOpen={isDeleteModalOpen}
        onClose={onDeleteModalClose}
        path={path}
        projectName={projectName}
        repoName={repoName}
      />
    </Box>
  );
};

export default FileEditor;
