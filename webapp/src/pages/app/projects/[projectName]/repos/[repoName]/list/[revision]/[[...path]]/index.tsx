import { InfoIcon } from '@chakra-ui/icons';
import {
  Box,
  Button,
  Flex,
  Heading,
  Spacer,
  Tab,
  TabList,
  TabPanel,
  TabPanels,
  Tabs,
  Tag,
  Tooltip,
} from '@chakra-ui/react';
import { useGetFilesQuery, useGetNormalisedRevisionQuery } from 'dogma/features/api/apiSlice';
import FileList from 'dogma/features/file/FileList';
import { useRouter } from 'next/router';
import HistoryList from 'dogma/features/history/HistoryList';
import { useState } from 'react';
import { createMessage, resetState } from 'dogma/features/message/messageSlice';
import { useAppDispatch } from 'dogma/store';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { CopySupport } from 'dogma/features/file/CopySupport';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { AiOutlinePlus } from 'react-icons/ai';
import Link from 'next/link';

const RepositoryDetailPage = () => {
  const router = useRouter();
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const revision = router.query.revision ? (router.query.revision as string) : 'head';
  const filePath = router.query.path ? `/${Array.from(router.query.path).join('/')}` : '';
  const directoryPath = router.asPath;
  const [tabIndex, setTabIndex] = useState(0);
  const dispatch = useAppDispatch();

  const handleTabChange = (index: number) => {
    setTabIndex(index);
  };

  const copyToClipboard = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      dispatch(createMessage({ title: '', text: 'copied to clipboard', type: 'success' }));
    } catch (err) {
      const error: string = ErrorHandler.handle(err);
      dispatch(createMessage({ title: 'failed to copy to clipboard', text: error, type: 'error' }));
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
        cliCommand += ` --revision ${revision}`;
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

  const {
    data: fileData,
    isLoading,
    error,
  } = useGetFilesQuery(
    { projectName, repoName, revision, filePath },
    {
      refetchOnMountOrArgChange: true,
      skip: false,
    },
  );
  const {
    data: revisionData,
    isLoading: isNormalRevisionLoading,
    error: isError,
  } = useGetNormalisedRevisionQuery({ projectName, repoName, revision: -1 });
  if (isLoading || isNormalRevisionLoading) {
    return <>Loading...</>;
  }
  if (error || isError) {
    return <>{JSON.stringify(error || isError)}</>;
  }

  return (
    <Box p="2">
      <Breadcrumbs path={directoryPath} omitIndexList={[0, 3, 5, 6]} suffixes={{ 4: '/list/head' }} />
      <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
        <Heading size="lg">{filePath || repoName} </Heading>
        <Tooltip label="Go to History to view all revisions">
          <Tag borderRadius="full" colorScheme="blue">
            Revision {revision} <InfoIcon ml={2} />
          </Tag>
        </Tooltip>
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
            <Flex>
              <Spacer />
              <Link href={`/app/projects/${projectName}/repos/${repoName}/new_file/head`}>
                <Button size="sm" rightIcon={<AiOutlinePlus />} colorScheme="teal">
                  New File
                </Button>
              </Link>
            </Flex>
            <FileList
              data={fileData || []}
              projectName={projectName}
              repoName={repoName}
              path={filePath}
              directoryPath={directoryPath}
              revision={revision}
              copySupport={clipboardCopySupport as CopySupport}
            />
          </TabPanel>
          <TabPanel>
            <HistoryList
              projectName={projectName}
              repoName={repoName}
              handleTabChange={handleTabChange}
              totalRevision={revisionData?.revision || 0}
            />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </Box>
  );
};

export default RepositoryDetailPage;
