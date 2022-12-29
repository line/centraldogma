import { AddIcon, InfoIcon } from '@chakra-ui/icons';
import {
  Box,
  Button,
  Drawer,
  DrawerOverlay,
  Flex,
  Heading,
  Spacer,
  Tab,
  TabList,
  TabPanel,
  TabPanels,
  Tabs,
  Tooltip,
  useDisclosure,
} from '@chakra-ui/react';
import {
  useGetFilesByProjectAndRepoAndRevisionNameQuery,
  useGetHistoryByProjectAndRepoNameQuery,
} from 'dogma/features/api/apiSlice';
import FileList from 'dogma/features/file/FileList';
import { useRouter } from 'next/router';
import { NewFileForm } from 'dogma/common/components/NewFileForm';
import HistoryList from 'dogma/features/history/HistoryList';
import { useState } from 'react';
import { Tag } from '@chakra-ui/react';
import { createMessage, resetState } from 'dogma/features/message/messageSlice';
import { useAppDispatch } from 'dogma/store';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { CopySupport } from 'dogma/features/file/CopySupport';

const RepositoryDetailPage = () => {
  const router = useRouter();
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const revision = router.query.revision ? (router.query.revision as string) : 'head';
  const { data: fileData = [] } = useGetFilesByProjectAndRepoAndRevisionNameQuery(
    { projectName, repoName, revision },
    {
      refetchOnMountOrArgChange: true,
      skip: false,
    },
  );
  const { data: historyData = [] } = useGetHistoryByProjectAndRepoNameQuery(
    { projectName, repoName },
    {
      refetchOnMountOrArgChange: true,
      skip: false,
    },
  );
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [tabIndex, setTabIndex] = useState(0);
  const dispatch = useAppDispatch();

  const handleTabChange = (index: number) => {
    setTabIndex(index);
  };

  const copyToClipboard = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      await dispatch(createMessage({ title: '', text: 'copied to clipboard', type: 'success' }));
    } catch (err) {
      const error: string = ErrorHandler.handle(err);
      await dispatch(createMessage({ title: 'failed to copy to clipboard', text: error, type: 'error' }));
    } finally {
      dispatch(resetState());
    }
  };

  const constructApiUrl = (project: string, repo: string, path: string): string => {
    let apiUrl = `${window.location.origin}/api/v1/projects/${project}/repos/${repo}/contents${path}`;
    if (revision !== 'head') {
      apiUrl += `?revision=${revision}`;
    }

    return apiUrl;
  };

  const clipboardCopySupport: CopySupport = {
    async handleApiUrl(project: string, repo: string, path: string) {
      const apiUrl: string = constructApiUrl(project, repo, path);
      copyToClipboard(apiUrl);
    },

    async handleWebUrl(project: string, repo: string, path: string) {
      const webUrl = `${window.location.origin}/app/projects/${project}/repos/${repo}/files/${revision}${path}`;
      copyToClipboard(webUrl);
    },

    async handleAsCliCommand(project: string, repo: string, path: string) {
      let cliCommand = `dogma --connect "${window.location.origin}" \\
--token "<access-token>" \\
cat ${project}/${repo}${path}`;

      if (revision !== 'head') {
        cliCommand += ` --revision=${revision}`;
      }

      copyToClipboard(cliCommand);
    },

    async handleAsCurlCommand(project: string, repo: string, path: string) {
      const apiUrl: string = constructApiUrl(project, repo, path);
      const curlCommand = `curl -XGET "${apiUrl}" \\
-H "Authorization: Bearer <access-token>"`;
      copyToClipboard(curlCommand);
    },
  };

  return (
    <Box p="2">
      <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
        <Heading size="lg">Repository {repoName} </Heading>
        <Tooltip label="Go to History to view all revisions">
          <Tag borderRadius="full" colorScheme="blue">
            Revision {revision} <InfoIcon ml={2} />
          </Tag>
        </Tooltip>
        <Spacer />
        <Button leftIcon={<AddIcon />} colorScheme="teal" onClick={onOpen} variant="ghost">
          New File
        </Button>
        <Drawer isOpen={isOpen} placement="right" onClose={onClose} size="xl">
          <DrawerOverlay />
          <NewFileForm />
        </Drawer>
      </Flex>
      <Tabs variant="enclosed-colored" size="lg" index={tabIndex} onChange={handleTabChange}>
        <TabList>
          <Tab>
            <Heading size="sm">Files</Heading>
          </Tab>
          <Tab>
            <Heading size="sm">History</Heading>
          </Tab>
        </TabList>
        <TabPanels>
          <TabPanel>
            <FileList
              data={fileData}
              projectName={projectName as string}
              repoName={repoName as string}
              copySupport={clipboardCopySupport as CopySupport}
            />
          </TabPanel>
          <TabPanel>
            <HistoryList
              data={historyData}
              projectName={projectName as string}
              repoName={repoName as string}
              handleTabChange={handleTabChange}
            />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </Box>
  );
};

export default RepositoryDetailPage;
