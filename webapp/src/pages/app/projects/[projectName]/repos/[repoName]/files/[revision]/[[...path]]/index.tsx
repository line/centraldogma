import { InfoIcon } from '@chakra-ui/icons';
import { Box, Flex, Heading, HStack, Tag, Tooltip } from '@chakra-ui/react';
import { useGetFileContentQuery, useGetHistoryQuery } from 'dogma/features/api/apiSlice';
import { useRouter } from 'next/router';
import FileEditor from 'dogma/common/components/editor/FileEditor';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { Deferred } from 'dogma/common/components/Deferred';
import React from 'react';
import { FileIcon } from 'dogma/common/components/FileIcon';
import { toFilePath } from 'dogma/util/path-util';

const FileContentPage = () => {
  const router = useRouter();
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const revision = router.query.revision ? (router.query.revision as string) : 'head';
  const filePath = toFilePath(router.query.path);
  const fileName = router.asPath
    .split('/')
    .filter((v) => v.length > 0)
    .pop();

  const fileExtension = fileName.substring(fileName.lastIndexOf('.') + 1);
  const { data, isLoading, error } = useGetFileContentQuery(
    { projectName, repoName, filePath, revision },
    {
      refetchOnMountOrArgChange: true,
    },
  );
  const {
    data: historyData,
    isLoading: isHistoryLoading,
    error: historyError,
  } = useGetHistoryQuery({
    projectName,
    repoName,
    revision,
    to: 1,
    filePath,
    maxCommits: 1,
  });
  return (
    <Deferred isLoading={isLoading || isHistoryLoading} error={error || historyError}>
      {() => {
        return (
          <Box p="2">
            <Breadcrumbs
              path={router.asPath}
              omitIndexList={[0, 5, 6]}
              unlinkedList={[3]}
              suffixes={{ 4: '/tree/head' }}
            />
            <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
              <Heading size="lg">
                <HStack color="teal">
                  <Box>
                    <FileIcon fileName={fileName} />
                  </Box>
                  <Box>{fileName}</Box>
                </HStack>
              </Heading>
              <Tooltip label="Go to History to view all revisions">
                <Tag borderRadius="full" colorScheme="blue">
                  Revision {revision} <InfoIcon ml={2} />
                </Tag>
              </Tooltip>
            </Flex>
            <FileEditor
              projectName={projectName}
              repoName={repoName}
              extension={fileExtension}
              originalContent={data.content}
              path={data.path}
              name={fileName}
              commitRevision={historyData[0].revision}
            />
          </Box>
        );
      }}
    </Deferred>
  );
};

export default FileContentPage;
