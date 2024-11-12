import { useRouter } from 'next/router';
import ProjectSettingsView from 'dogma/features/project/settings/ProjectSettingsView';
import { DeleteProject } from 'dogma/features/project/DeleteProject';
import { Box } from '@chakra-ui/react';

const DangerZonePage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  return (
    <ProjectSettingsView projectName={projectName} currentTab={'danger zone'}>
      {() => (
        <Box padding={3}>
          <DeleteProject projectName={projectName} />
        </Box>
      )}
    </ProjectSettingsView>
  );
};

export default DangerZonePage;
