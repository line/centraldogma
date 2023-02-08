import { Box } from '@chakra-ui/react';
import { useRouter } from 'next/router';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { NewFile } from 'dogma/common/components/NewFile';

const NewFilePage = () => {
  const router = useRouter();
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  return (
    <Box p="2">
      <Breadcrumbs path={router.asPath} omitIndexList={[0, 3, 5, 6]} suffixes={{ 4: '/list/head' }} />
      <NewFile
        projectName={projectName}
        repoName={repoName}
        initialPrefixes={(router.query.path as string[]) || []}
      />
    </Box>
  );
};

export default NewFilePage;
