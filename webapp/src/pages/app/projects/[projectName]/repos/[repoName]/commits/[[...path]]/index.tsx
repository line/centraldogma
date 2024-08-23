import { Box, Flex, Heading, HStack } from '@chakra-ui/react';
import { useGetNormalisedRevisionQuery } from 'dogma/features/api/apiSlice';
import { useRouter } from 'next/router';
import HistoryList from 'dogma/features/history/HistoryList';
import React from 'react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { Deferred } from 'dogma/common/components/Deferred';
import { FcOpenedFolder } from 'react-icons/fc';
import { GoRepo } from 'react-icons/go';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { makeTraversalFileLinks } from 'dogma/util/path-util';

const HistoryListPage = () => {
  const router = useRouter();
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const filePath = router.query.path ? `/${Array.from(router.query.path).join('/')}` : '';
  const directoryPath = router.asPath;

  const { data, isLoading, error } = useGetNormalisedRevisionQuery({ projectName, repoName, revision: -1 });

  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <Box p="2">
          <Breadcrumbs path={directoryPath} omitIndexList={[0]} unlinkedList={[3]} />
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
                  <Box fontWeight={'normal'}>&nbsp;commits</Box>
                </HStack>
              ) : (
                <HStack>
                  <Box color={'teal'}>
                    <GoRepo />
                  </Box>
                  <Box color={'teal'}>
                    <ChakraLink href={`/app/projects/${projectName}/repos/${repoName}`}>{repoName}</ChakraLink>
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
            isDirectory={true}
            totalRevision={data?.revision || 0}
          />
        </Box>
      )}
    </Deferred>
  );
};

export default HistoryListPage;
