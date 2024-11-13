import { Box, Button, Flex, Heading, HStack, Icon, useColorModeValue, VStack } from '@chakra-ui/react';
import { useGetHistoryQuery, useGetNormalisedRevisionQuery } from 'dogma/features/api/apiSlice';
import { useRouter } from 'next/router';
import HistoryList from 'dogma/features/history/HistoryList';
import React from 'react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { Deferred } from 'dogma/common/components/Deferred';
import { FcOpenedFolder } from 'react-icons/fc';
import { GoRepo } from 'react-icons/go';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { makeTraversalFileLinks, toFilePath } from 'dogma/util/path-util';
import { PaginationState } from '@tanstack/react-table';
import { MdErrorOutline } from 'react-icons/md';
import { FileIcon } from 'dogma/common/components/FileIcon';

const DEFAULT_PAGE_SIZE = 10;

const HistoryListPage = () => {
  const router = useRouter();
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const filePath = router.query.path ? toFilePath(router.query.path) : '';
  const from = parseInt(router.query.from as string) || -1;
  const directoryPath = router.asPath;
  let type = router.query.type as string;
  if (!type && !filePath) {
    type = 'tree';
  }
  const targetPath = type == 'tree' ? `${filePath}/**` : filePath;

  const { data, isLoading, error } = useGetNormalisedRevisionQuery({ projectName, repoName, revision: -1 });
  const headRevision = data?.revision || -1;
  let fromRevision = from;
  if (from <= -1) {
    fromRevision = headRevision + from + 1;
  }
  let baseRevision = parseInt(router.query.base as string) || fromRevision - DEFAULT_PAGE_SIZE + 1;
  const pageSize = fromRevision - baseRevision + 1;
  if (baseRevision < 1) {
    baseRevision = 1;
  }

  const fromPageCount = Math.ceil(fromRevision / pageSize);
  let headPageCount;
  if (fromRevision > headRevision) {
    headPageCount = 0;
  } else {
    headPageCount = Math.ceil((headRevision - fromRevision) / pageSize);
  }

  const pageCount = headPageCount + fromPageCount;
  const pageIndex = headPageCount;
  const pagination = { pageIndex, pageSize };
  function setPagination(updater: (old: PaginationState) => PaginationState): void {
    if (headRevision <= 0) {
      return;
    }

    const newPagination = updater({ pageIndex, pageSize });
    let newPageSize = newPagination.pageSize;
    if (newPageSize == -1) {
      newPageSize = pageSize;
    }
    const newPageIndex = newPagination.pageIndex;

    if (newPageIndex != pageIndex || newPageSize != pageSize) {
      const from = fromRevision - (newPageIndex - pageIndex) * newPageSize;
      const base = from - newPageSize + 1;
      const query: { [key: string]: string | number } = { from, base };
      if (type) {
        query['type'] = type;
      }
      router.push({
        pathname: `/app/projects/${projectName}/repos/${repoName}/commits/${filePath}`,
        query,
      });
      return;
    }
  }

  const historyFrom = Math.min(fromRevision, headRevision);
  const historyTo = Math.max(baseRevision, 1);
  const {
    data: historyData,
    isLoading: isHistoryLoading,
    error: historyError,
  } = useGetHistoryQuery(
    {
      projectName,
      repoName,
      filePath: targetPath,
      revision: historyFrom,
      to: historyTo,
      maxCommits: historyFrom - historyTo + 1,
    },
    {
      skip: headRevision === -1 || historyFrom < historyTo,
    },
  );

  const onEmptyData = (
    <VStack marginBottom={10} bg={useColorModeValue('gray.100', 'gray.900')} padding={10}>
      <HStack marginBottom={5}>
        <Heading size={'md'} alignSelf={'center'}>
          <Icon as={MdErrorOutline} size={'md'} marginBottom={-0.5} marginRight={1} />
          No changes detected for {filePath} in range [{baseRevision}..{fromRevision}]
        </Heading>
      </HStack>
      <Button
        size={'sm'}
        colorScheme={'green'}
        as={ChakraLink}
        href={`/app/projects/${projectName}/repos/${repoName}/commits/${filePath}?from=${baseRevision - 1}&base=${baseRevision - 100}${type ? `&type=${type}` : ''}`}
      >
        Go to older commits
      </Button>
    </VStack>
  );

  const urlAndSegments = makeTraversalFileLinks(projectName, repoName, 'commits', filePath);

  return (
    <Deferred isLoading={isLoading || isHistoryLoading} error={error || historyError}>
      {() => {
        const omitQueryList = [1, 2, 4, 5];
        if (type !== 'tree' && router.query.from) {
          // Omit the 'type=tree' query parameter when the type is a file.
          omitQueryList.push(-2);
        }
        return (
          <Box p="2">
            <Breadcrumbs
              path={
                directoryPath.split('?')[0] + (router.query.from ? `/${baseRevision}..${fromRevision}` : '')
              }
              omitIndexList={[0, 3]}
              omitQueryList={omitQueryList}
              query="type=tree"
            />
            <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
              <Heading size="lg">
                {filePath ? (
                  <HStack gap={0}>
                    <Box color={'teal'} marginRight={2} marginBottom={-2}>
                      {type === 'tree' ? <Icon as={FcOpenedFolder} /> : <FileIcon fileName={filePath} />}
                    </Box>
                    {urlAndSegments.map(({ segment, url }, index) => {
                      let query = '';
                      if (type === 'tree' || index < urlAndSegments.length - 1) {
                        query = '?type=tree';
                      }
                      const targetUrl = url + query;
                      return (
                        <Box key={targetUrl}>
                          {'/'}
                          <ChakraLink href={targetUrl}>{segment}</ChakraLink>
                        </Box>
                      );
                    })}
                    <Box fontWeight={'normal'}>&nbsp;commits</Box>
                  </HStack>
                ) : (
                  <HStack>
                    <Box color={'teal'}>
                      <GoRepo />
                    </Box>
                    <Box color={'teal'}>
                      <ChakraLink href={`/app/projects/${projectName}/repos/${repoName}`}>
                        {repoName}
                      </ChakraLink>
                    </Box>
                    <Box>{filePath} commits</Box>
                  </HStack>
                )}
              </Heading>
            </Flex>
            <HistoryList
              projectName={projectName}
              repoName={repoName}
              filePath={filePath}
              data={historyData || []}
              pagination={pagination}
              setPagination={setPagination}
              pageCount={pageCount}
              onEmptyData={onEmptyData}
              isDirectory={type === 'tree'}
            />
          </Box>
        );
      }}
    </Deferred>
  );
};

export default HistoryListPage;
