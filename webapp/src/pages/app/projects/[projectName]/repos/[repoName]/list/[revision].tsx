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
  const handleTabChange = (index: number) => {
    setTabIndex(index);
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
            <FileList data={fileData} projectName={projectName as string} repoName={repoName as string} />
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
