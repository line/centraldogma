import {
  Box,
  Button,
  Flex,
  Heading,
  Input,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
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

export type FileEditorProps = {
  language: string;
  originalContent: string;
};

const FileEditor = ({ language, originalContent }: FileEditorProps) => {
  const [tabIndex, setTabIndex] = useState(0);
  const handleTabChange = (index: number) => {
    setTabIndex(index);
  };
  const [fileContent, setFileContent] = useState('');
  const editorRef = useRef(null);
  const handleEditorMount: OnMount = (editor) => {
    editorRef.current = editor;
    setFileContent(
      language === 'json' ? JSON.stringify(JSON.parse(originalContent), null, 2) : originalContent,
    );
  };
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [readOnly, setReadOnly] = useState(true);
  const returnToViewMode = () => (readOnly ? setReadOnly(false) : onOpen());
  const resetViewEditor = () => {
    editorRef.current.setValue(fileContent);
    setReadOnly(true);
    setTabIndex(0);
    onClose();
  };
  const { colorMode } = useColorMode();
  const [diffSideBySide, setDiffSideBySide] = useState(false);
  const [editorExpanded, setEditorExpanded] = useState(false);
  return (
    <Box>
      <Flex>
        <Spacer />
        <Button
          onClick={returnToViewMode}
          leftIcon={readOnly ? <FcEditImage /> : <FcCancel />}
          colorScheme={readOnly ? 'teal' : 'blue'}
          variant="ghost"
        >
          {readOnly ? 'Edit' : 'Cancel changes'}
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
              <Flex mb="2">
                <Spacer />
                <EditModeToggle
                  switchMode={() => setEditorExpanded(!editorExpanded)}
                  value={!editorExpanded}
                  label="Expand"
                />
              </Flex>
              <Editor
                height={editorExpanded ? editorRef.current.getModel().getLineCount() * 20 : '50vh'}
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
      <VStack p={4} gap="2" mb={6} align="stretch" display={readOnly ? 'none' : 'visible'}>
        <Heading size="md">Commit changes</Heading>
        <Input name="summary" placeholder="Add a summary" />
        <Textarea placeholder="Add an optional extended description..." />
        <Stack direction="row" spacing={4} mt={2}>
          <Button colorScheme="teal">Commit</Button>
          <Button variant="outline" onClick={returnToViewMode}>
            Cancel
          </Button>
        </Stack>
      </VStack>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>Your changes will be discarded!</ModalBody>
          <ModalFooter>
            <Button colorScheme="red" mr={3} onClick={resetViewEditor}>
              Discard changes
            </Button>
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </Box>
  );
};

export default FileEditor;
