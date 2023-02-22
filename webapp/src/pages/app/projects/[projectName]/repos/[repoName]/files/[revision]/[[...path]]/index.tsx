import { InfoIcon } from '@chakra-ui/icons';
import { Box, Flex, Heading, Tag, Tooltip } from '@chakra-ui/react';
import { useGetFileContentQuery } from 'dogma/features/api/apiSlice';
import { useRouter } from 'next/router';
import FileEditor from 'dogma/common/components/editor/FileEditor';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { Deferred } from 'dogma/common/components/Deferred';

const FileContentPage = () => {
  const router = useRouter();
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const revision = router.query.revision ? (router.query.revision as string) : 'head';
  const filePath = router.query.path ? `${Array.from(router.query.path).join('/')}`.replace(/\/\//g, '/') : '';
  const fileName = router.asPath
    .split('/')
    .filter((v) => v.length > 0)
    .pop();
  const { data, isLoading, error } = useGetFileContentQuery(
    { projectName, repoName, filePath },
    {
      refetchOnMountOrArgChange: true,
      skip: false,
    },
  );
  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <Box p="2">
          <Breadcrumbs path={router.asPath} omitIndexList={[0, 3, 5, 6]} suffixes={{ 4: '/list/head' }} />
          <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
            <Heading size="lg">{fileName}</Heading>
            <Tooltip label="Go to History to view all revisions">
              <Tag borderRadius="full" colorScheme="blue">
                Revision {revision} <InfoIcon ml={2} />
              </Tag>
            </Tooltip>
          </Flex>
          <FileEditor
            projectName={projectName}
            repoName={repoName}
            language={data.type.toLowerCase()}
            originalContent={data.content}
            path={data.path}
            name={fileName}
          />
        </Box>
      )}
    </Deferred>
  );
};

export default FileContentPage;
