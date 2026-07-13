import { Box, Tooltip } from '@chakra-ui/react';
import { MetadataButton } from 'dogma/common/components/MetadataButton';
import { WithProjectRole } from 'dogma/features/auth/ProjectRole';
import { PROJECT_READ_ONLY_HINT, useProjectReadOnly } from 'dogma/features/repo/useReadOnly';

export const ProjectSettingsButton = ({ projectName }: { projectName: string }) => {
  const projectReadOnly = useProjectReadOnly(projectName);
  if (projectName === 'dogma') {
    return null;
  }
  return (
    <WithProjectRole projectName={projectName} roles={['OWNER', 'MEMBER']}>
      {() => (
        <Tooltip label={PROJECT_READ_ONLY_HINT} isDisabled={!projectReadOnly}>
          <Box>
            <MetadataButton
              href={`/app/projects/${projectName}/settings`}
              props={{ size: 'sm' }}
              text={'Project Settings'}
              isDisabled={projectReadOnly}
            />
          </Box>
        </Tooltip>
      )}
    </WithProjectRole>
  );
};
