import { useRouter } from 'next/router';
import {
  useGetFilesQuery,
  useGetHistoryQuery,
  useGetNormalisedRevisionQuery,
} from 'dogma/features/api/apiSlice';
import { Deferred } from 'dogma/common/components/Deferred';
import {
  Badge,
  Box,
  Button,
  ButtonGroup,
  Heading,
  HStack,
  Spacer,
  Text,
  Tooltip,
  useColorMode,
  useColorModeValue,
} from '@chakra-ui/react';
import React, { useState } from 'react';
import { Author } from 'dogma/common/components/Author';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { GrFormNext, GrFormPrevious } from 'react-icons/gr';
import FourOhFour from 'pages/404';
import { toFilePath } from 'dogma/util/path-util';
import { GoCodescan, GoCommit } from 'react-icons/go';
import { FaHistory } from 'react-icons/fa';
import DiffView, { DiffMode } from 'dogma/common/components/DiffView';
import DiffModeButton from 'dogma/common/components/DiffModeButton';

const CommitViewPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const repoName = router.query.repoName as string;
  const revision = parseInt(router.query.revision as string);
  let filePath = toFilePath(router.query.path);
  if (filePath == '/') {
    filePath = '/**';
  }
  const {
    data: historyData,
    isLoading: historyLoading,
    error: historyError,
  } = useGetHistoryQuery({
    projectName,
    repoName,
    revision,
    to: 1,
    filePath,
    maxCommits: 1,
  });
  const {
    data: oldHistoryData,
    isLoading: oldHistoryLoading,
    error: oldHistoryError,
  } = useGetHistoryQuery(
    {
      projectName,
      repoName,
      revision: revision - 1,
      to: 1,
      filePath,
      maxCommits: 1,
    },
    {
      skip: revision <= 2,
    },
  );
  const {
    data: newData,
    isLoading: isNewLoading,
    error: newError,
  } = useGetFilesQuery({
    projectName,
    repoName,
    revision,
    filePath,
    withContent: true,
  });
  const {
    data: oldData,
    isLoading: isOldLoading,
    error: oldError,
  } = useGetFilesQuery(
    {
      projectName,
      repoName,
      revision: oldHistoryData?.[0]?.revision || 1,
      filePath,
      withContent: true,
    },
    {
      skip: !oldHistoryData || oldHistoryData.length == 0,
    },
  );
  const {
    data: headRev,
    isLoading: isRevLoading,
    error: revError,
  } = useGetNormalisedRevisionQuery({
    projectName,
    repoName,
    revision: -1,
  });

  const { colorMode } = useColorMode();
  const commitTitleColorMode = useColorModeValue('gray.100', 'gray.600');
  const commitMessageColorMode = useColorModeValue('gray.300', 'gray.700');
  const [diffMode, setDiffMode] = useState<DiffMode>('Split');

  if (revision <= 1) {
    return <FourOhFour title={`Revision ${revision} does not exist..`} />;
  }
  // 404 Not Found is returned if the file does not exist in the old revision.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const oldError0 = oldError && (oldError as any).status != 404 ? oldError : null;

  return (
    <Deferred
      isLoading={historyLoading || oldHistoryLoading || isNewLoading || isOldLoading || isRevLoading}
      error={historyError || oldHistoryError || newError || revError || oldError0}
    >
      {() => {
        if (!historyData || historyData.length == 0) {
          return <FourOhFour title={`Revision ${revision} for '${filePath}' does not exist..`} />;
        }

        const history = historyData[0];
        let previousRevision;
        if (revision > 2 && oldHistoryData && oldHistoryData.length > 0) {
          previousRevision = oldHistoryData[0].revision;
        }
        const oldData0 = !previousRevision ? [] : oldData || [];
        const newData0 = newData || [];
        const hasNext = filePath == '/**' && revision !== headRev.revision;
        return (
          <Box>
            <Breadcrumbs path={router.asPath} omitIndexList={[0, 3]} unlinkedList={[5]} />
            <Heading size="lg" marginBottom={5}>
              <HStack color="teal">
                <Box>
                  <GoCommit />
                </Box>
                <Box>Commit {revision}</Box>
              </HStack>
            </Heading>
            <HStack marginBottom={2}>
              <Spacer />
              <ButtonGroup marginRight={1}>
                <Tooltip label="Move to the previous commit">
                  {previousRevision > 0 ? (
                    <Button
                      as={ChakraLink}
                      marginRight={-1}
                      borderColor={'gray.300'}
                      borderWidth="1px"
                      size={'sm'}
                      href={`/app/projects/${projectName}/repos/${repoName}/commit/${previousRevision}${filePath == '/**' ? '' : filePath}`}
                    >
                      <GrFormPrevious />
                    </Button>
                  ) : (
                    <Button
                      isDisabled={true}
                      marginRight={-1}
                      borderColor={'gray.300'}
                      borderWidth="1px"
                      size={'sm'}
                    >
                      <GrFormPrevious />
                    </Button>
                  )}
                </Tooltip>
                {filePath === '/**' && (
                  // Reverse traversal is supported only for the whole repository.
                  // Because the Git history is not linear for a file or a directory.
                  <Tooltip label={'Move to the next commit'}>
                    {hasNext ? (
                      <Button
                        as={ChakraLink}
                        borderColor={'gray.300'}
                        borderWidth="1px"
                        href={`/app/projects/${projectName}/repos/${repoName}/commit/${revision + 1}${filePath == '/**' ? '' : filePath}`}
                        size={'sm'}
                      >
                        <GrFormNext />
                      </Button>
                    ) : (
                      <Button isDisabled={true} borderColor={'gray.300'} borderWidth="1px" size={'sm'}>
                        <GrFormNext />
                      </Button>
                    )}
                  </Tooltip>
                )}
              </ButtonGroup>
              <DiffModeButton onChange={(value) => setDiffMode(value as DiffMode)} />
              <Button
                colorScheme={'green'}
                leftIcon={<FaHistory />}
                fontWeight={'normal'}
                marginLeft={1}
                size={'sm'}
                as={ChakraLink}
                href={`/app/projects/${projectName}/repos/${repoName}/commits`}
              >
                View commits
              </Button>
              <Button
                colorScheme={'blue'}
                leftIcon={<GoCodescan />}
                fontWeight={'normal'}
                size={'sm'}
                as={ChakraLink}
                href={`/app/projects/${projectName}/repos/${repoName}/tree/${revision}`}
              >
                Browse files
              </Button>
            </HStack>
            <Box padding={3} bg={commitTitleColorMode}>
              <Heading size="md" paddingBottom={2}>
                {history.commitMessage.summary}
              </Heading>
              <Text>{history.commitMessage.detail}</Text>
            </Box>
            <HStack padding={3} marginBottom={4} bg={commitMessageColorMode}>
              <Author name={history.author.name} />
              <Box>committed on {new Date(history.pushedAt).toLocaleString()}</Box>
              <Spacer />
              <Badge colorScheme={'darkgray'}>Revision {revision}</Badge>
            </HStack>
            <DiffView
              projectName={projectName}
              repoName={repoName}
              revision={revision}
              oldData={oldData0}
              newData={newData0}
              diffMode={diffMode}
              colorMode={colorMode}
            />
          </Box>
        );
      }}
    </Deferred>
  );
};

export default CommitViewPage;
