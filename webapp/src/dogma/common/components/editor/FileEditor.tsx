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
import React, { useRef, useState } from 'react';
import { FcCancel, FcEditImage } from 'react-icons/fc';
import { JsonPath } from 'dogma/common/components/editor/JsonPath';
import { JsonPathLegend } from 'dogma/common/components/editor/JsonPathLegend';
import { AiOutlineDelete } from 'react-icons/ai';
import { DiscardChangesModal } from 'dogma/common/components/editor/DiscardChangesModal';
import { DeleteFileModal } from 'dogma/common/components/editor/DeleteFileModal';
import { CommitForm } from 'dogma/common/components/CommitForm';
import Router from 'next/router';
import Link from 'next/link';
import { FaHistory } from 'react-icons/fa';
import { registerJson5Language } from 'dogma/features/file/Json5Language';
import { useLocalMonaco } from 'dogma/features/file/MonacoLoader';
import { Loading } from 'dogma/common/components/Loading';

export type FileEditorProps = {
  projectName: string;
  repoName: string;
  extension: string;
  originalContent: string;
  path: string;
  name: string;
  revision: string | number;
};

// Map file extension to language identifier
export const extensionToLanguageMap: { [key: string]: string } = {
  js: 'javascript',
  ts: 'typescript',
  html: 'html',
  css: 'css',
  json: 'json',
  json5: 'json5',
  xml: 'xml',
  md: 'markdown',
  py: 'python',
  yml: 'yaml',
  yaml: 'yaml',
  dockerfile: 'dockerfile',
  sass: 'sass',
  less: 'less',
  scss: 'scss',
  toml: 'toml',
};

const FileEditor = ({
  projectName,
  repoName,
  extension,
  originalContent,
  path,
  name,
  revision,
}: FileEditorProps) => {
  const language = extensionToLanguageMap[extension] || extension;
  const [tabIndex, setTabIndex] = useState(0);
  const handleTabChange = (index: number) => {
    setTabIndex(index);
  };
  let displayContent = originalContent;
  if (path.endsWith('.json') && originalContent.indexOf('\n') === -1) {
    // Pretty print JSON content if it's a single line which is hard to read.
    // If the file is not created from a raw JSON, the server will normalize it and write a compact JSON.
    displayContent = JSON.stringify(JSON.parse(originalContent), null, 2);
  }
  const [fileContent, setFileContent] = useState('');
  const editorRef = useRef(null);
  const handleEditorMount: OnMount = (editor) => {
    editorRef.current = editor;
    setFileContent(displayContent);
  };

  const { isOpen: isCancelModalOpen, onOpen: onCancelModalOpen, onClose: onCancelModalClose } = useDisclosure();
  const { isOpen: isDeleteModalOpen, onOpen: onDeleteModalOpen, onClose: onDeleteModalClose } = useDisclosure();
  const [readOnly, setReadOnly] = useState(true);
  const switchMode = () => {
    if (readOnly) {
      setFileContent(displayContent);
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

  const monaco = useLocalMonaco();
  if (!monaco) {
    return <Loading />;
  }

  return (
    <Box>
      <Flex gap={4}>
        <Spacer />
        <Button
          size={'sm'}
          as={Link}
          href={`/app/projects/${projectName}/repos/${repoName}/commits/${path}${revision !== 'head' ? `?from=${revision}` : ''}`}
          leftIcon={<FaHistory />}
          variant="outline"
          colorScheme="gray"
        >
          History
        </Button>
        {projectName !== 'dogma' && repoName !== 'dogma' && (
          <>
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
          </>
        )}
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
                <JsonPath setFileContent={setFileContent} jsonContent={JSON.parse(originalContent)} />
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
                beforeMount={registerJson5Language}
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
      <CommitForm
        projectName={projectName}
        repoName={repoName}
        path={path}
        name={name}
        content={() => editorRef?.current?.getValue()}
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
