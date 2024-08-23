import {InfoIcon} from '@chakra-ui/icons';
import {Box, Button, Flex, Heading, HStack, Spacer, Tag, Tooltip} from '@chakra-ui/react';
import {useGetFilesQuery} from 'dogma/features/api/apiSlice';
import FileList from 'dogma/features/file/FileList';
import {useRouter} from 'next/router';
import React from 'react';
import {newNotification, resetState} from 'dogma/features/notification/notificationSlice';
import {useAppDispatch} from 'dogma/hooks';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import {CopySupport} from 'dogma/features/file/CopySupport';
import {Breadcrumbs} from 'dogma/common/components/Breadcrumbs';
import {AiOutlinePlus} from 'react-icons/ai';
import Link from 'next/link';
import {MetadataButton} from 'dogma/common/components/MetadataButton';
import {Deferred} from 'dogma/common/components/Deferred';
import {FcOpenedFolder} from 'react-icons/fc';
import {GoRepo} from 'react-icons/go';
import {ChakraLink} from 'dogma/common/components/ChakraLink';
import {WithProjectRole} from 'dogma/features/auth/ProjectRole';
import {FaHistory} from 'react-icons/fa';
import {makeTraversalFileLinks} from "dogma/util/path-util";

const RepositoryDetailPage = () => {
  const router = useRouter();
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const revision = router.query.revision ? (router.query.revision as string) : 'head';
  const filePath = router.query.path ? `/${Array.from(router.query.path).join('/')}` : '';
  const directoryPath = router.asPath;
  const dispatch = useAppDispatch();

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

  const { data, isLoading, error } = useGetFilesQuery(
    { projectName, repoName, revision, filePath, withContent: false },
    {
      refetchOnMountOrArgChange: true,
    },
  );

  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => {
        let files = data || [];
        files = Array.isArray(files) ? files : [files];
        return (
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
              <Spacer />
            </Flex>
            <Flex gap={2}>
              <Spacer />
              <Button
                size={'sm'}
                as={Link}
                href={`/app/projects/${projectName}/repos/${repoName}/commits${filePath}`}
                leftIcon={<FaHistory />}
                variant="outline"
                colorScheme="gray"
              >
                History
              </Button>
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
              data={files}
              projectName={projectName}
              repoName={repoName}
              path={filePath}
              directoryPath={directoryPath}
              revision={revision}
              copySupport={clipboardCopySupport as CopySupport}
            />
          </Box>
        );
      }}
    </Deferred>
  );
};

export default RepositoryDetailPage;
