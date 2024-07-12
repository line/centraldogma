import {
  Box,
  Button,
  Divider,
  Flex,
  Heading,
  Spacer,
  Tab,
  TabList,
  TabPanel,
  TabPanels,
  Tabs,
  useColorMode,
  useDisclosure,
} from '@chakra-ui/react';
import Editor, { DiffEditor, OnMount } from '@monaco-editor/react';
import { EditModeToggle } from 'dogma/common/components/editor/EditModeToggle';
import React, { useState, useRef } from 'react';
import { FcEditImage, FcCancel } from 'react-icons/fc';
import { JsonPath } from 'dogma/common/components/editor/JsonPath';
import { JsonPathLegend } from 'dogma/common/components/editor/JsonPathLegend';
import { AiOutlineDelete } from 'react-icons/ai';
import { DiscardChangesModal } from 'dogma/common/components/editor/DiscardChangesModal';
import { DeleteFileModal } from 'dogma/common/components/editor/DeleteFileModal';
import { CommitForm } from 'dogma/common/components/CommitForm';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import Router from 'next/router';

export type FileEditorProps = {
  projectName: string;
  repoName: string;
  language: string;
  originalContent: string;
  path: string;
  name: string;
};

const FileEditor = ({ projectName, repoName, language, originalContent, path, name }: FileEditorProps) => {
  const dispatch = useAppDispatch();
  let jsonContent = '';
  if (language === 'json') {
    try {
      jsonContent = JSON.parse(
        typeof originalContent === 'string' ? originalContent : JSON.stringify(originalContent),
      );
    } catch (error) {
      dispatch(
        newNotification(
          `Failed to format json content.`,
          ErrorMessageParser.parse(error),
          'error',
        ),
      );
    }
  }
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
                {readOnly && language === 'json' && jsonContent ? <JsonPathLegend /> : ''}
              </Flex>
              {readOnly && language === 'json' && jsonContent ? (
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
                value={typeof fileContent === 'string' ? fileContent : JSON.stringify(fileContent)}
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
              original={typeof fileContent === 'string' ? fileContent : JSON.stringify(fileContent)}
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
      <CommitForm
        projectName={projectName}
        repoName={repoName}
        path={path}
        name={name}
        content={editorRef?.current?.getValue()}
        readOnly={readOnly}
        setReadOnly={setReadOnly}
        switchMode={switchMode}
        handleTabChange={handleTabChange}
      />
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
        onSuccess={() => {
          Router.push(`/app/projects/${projectName}/repos/${repoName}/tree/head/`);
        }}
      />
    </Box>
  );
};

export default FileEditor;
