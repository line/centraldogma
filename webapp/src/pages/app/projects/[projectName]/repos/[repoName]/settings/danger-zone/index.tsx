import { useRouter } from 'next/router';
import { Box } from '@chakra-ui/react';
import RepositorySettingsView from 'dogma/features/repo/settings/RepositorySettingsView';
import { DeleteRepo } from 'dogma/features/repo/DeleteRepo';

const DangerZonePage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  return (
    <RepositorySettingsView projectName={projectName} repoName={repoName} currentTab={'Danger Zone'}>
      {() => (
        <Box padding={3}>
          <DeleteRepo
            projectName={projectName}
            repoName={repoName}
            hidden={false}
            buttonVariant={'outline'}
            buttonSize={'lg'}
          />
        </Box>
      )}
    </RepositorySettingsView>
  );
};

export default DangerZonePage;
