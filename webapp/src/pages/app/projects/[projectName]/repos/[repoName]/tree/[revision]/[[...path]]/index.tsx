import { InfoIcon } from '@chakra-ui/icons';
import {
  Box,
  Button,
  Flex,
  Heading,
  HStack,
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
import React, { useState } from 'react';
import { newNotification, resetState } from 'dogma/features/notification/notificationSlice';
import { useAppDispatch } from 'dogma/hooks';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { CopySupport } from 'dogma/features/file/CopySupport';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { AiOutlinePlus } from 'react-icons/ai';
import Link from 'next/link';
import { MetadataButton } from 'dogma/common/components/MetadataButton';
import { Deferred } from 'dogma/common/components/Deferred';
import { FcOpenedFolder } from 'react-icons/fc';
import { GoRepo } from 'react-icons/go';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { WithProjectRole } from 'dogma/features/auth/ProjectRole';

type UrlAndSegment = {
  segment: string;
  url: string;
};

function makeTraversalFileLinks(projectName: string, repoName: string, path: string): UrlAndSegment[] {
  const links: UrlAndSegment[] = [];
  const segments = path.split('/');
  for (let i = 1; i < segments.length; i++) {
    const url = `/app/projects/${projectName}/repos/${repoName}/tree/head/${segments.slice(1, i + 1).join('/')}`;
    links.push({ segment: segments[i], url });
  }
  return links;
}

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
      dispatch(newNotification('', 'copied to clipboard', 'success'));
    } catch (err) {
      const error: string = ErrorMessageParser.parse(err);
      dispatch(newNotification('failed to copy to clipboard', error, 'error'));
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
    isLoading: isGetFilesLoading,
    error: isGetFilesError,
  } = useGetFilesQuery(
    { projectName, repoName, revision, filePath },
    {
      refetchOnMountOrArgChange: true,
    },
  );
  const {
    data: revisionData,
    isLoading: isNormalRevisionLoading,
    error: isNormalRevisionError,
  } = useGetNormalisedRevisionQuery({ projectName, repoName, revision: -1 });

  return (
    <Deferred
      isLoading={isGetFilesLoading || isNormalRevisionLoading}
      error={isGetFilesError || isNormalRevisionError}
    >
      {() => (
        <Box p="2">
          <Breadcrumbs
            path={directoryPath}
            omitIndexList={[0, 5, 6]}
            unlinkedList={[3]}
            suffixes={{ 4: '/tree/head' }}
          />
          <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
            <Heading size="lg">
              {filePath ? (
                <HStack gap={0}>
                  <Box color={'teal'} marginRight={2}>
                    <FcOpenedFolder />
                  </Box>
                  {makeTraversalFileLinks(projectName, repoName, filePath).map(({ segment, url }) => {
                    return (
                      <Box key={url}>
                        {'/'}
                        <ChakraLink href={url}>{segment}</ChakraLink>
                      </Box>
                    );
                  })}
                </HStack>
              ) : (
                <HStack>
                  <Box color={'teal'}>
                    <GoRepo />
                  </Box>
                  <Box color={'teal'}>{repoName}</Box>
                </HStack>
              )}
            </Heading>
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
                <Flex gap={2}>
                  <Spacer />
                  {projectName == 'dogma' ? null : (
                    <WithProjectRole projectName={projectName} roles={['OWNER']}>
                      {() => (
                        <MetadataButton
                          href={`/app/projects/${projectName}/repos/${repoName}/permissions`}
                          props={{ size: 'sm' }}
                          text={'Repository Permissions'}
                        />
                      )}
                    </WithProjectRole>
                  )}
                  <Button
                    as={Link}
                    href={`/app/projects/${projectName}/repos/${repoName}/files/new${filePath}`}
                    size="sm"
                    rightIcon={<AiOutlinePlus />}
                    colorScheme="teal"
                  >
                    New File
                  </Button>
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
      )}
    </Deferred>
  );
};

export default RepositoryDetailPage;
