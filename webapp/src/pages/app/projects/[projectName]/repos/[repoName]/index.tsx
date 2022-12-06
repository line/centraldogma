import { AddIcon } from '@chakra-ui/icons';
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
  useDisclosure,
} from '@chakra-ui/react';
import { useGetFilesByProjectAndRepoNameQuery } from 'dogma/features/api/apiSlice';
import FileList from 'dogma/features/file/FileList';
import { useRouter } from 'next/router';
import { NewFileForm } from 'dogma/common/components/NewFileForm';

const RepositoryDetailPage = () => {
  const router = useRouter();
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const { data = [] } = useGetFilesByProjectAndRepoNameQuery(
    { projectName, repoName },
    {
      refetchOnMountOrArgChange: true,
      skip: false,
    },
  );
  const { isOpen, onOpen, onClose } = useDisclosure();
  return (
    <Box p="2">
      <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
        <Heading size="lg">Repository {repoName}</Heading>
        <Spacer />
        <Button leftIcon={<AddIcon />} colorScheme="teal" onClick={onOpen} variant="ghost">
          New File
        </Button>
        <Drawer isOpen={isOpen} placement="right" onClose={onClose} size="xl">
          <DrawerOverlay />
          <NewFileForm />
        </Drawer>
      </Flex>
      <Tabs variant="enclosed-colored" size="lg">
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
            <FileList data={data} projectName={projectName as string} repoName={repoName as string} />
          </TabPanel>
          <TabPanel>TODO: History</TabPanel>
        </TabPanels>
      </Tabs>
    </Box>
  );
};

export default RepositoryDetailPage;
