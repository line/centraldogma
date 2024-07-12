import { MetadataButton } from 'dogma/common/components/MetadataButton';
import { WithProjectRole } from 'dogma/features/auth/ProjectRole';

export const ProjectSettingsButton = ({ projectName }: { projectName: string }) => {
  if (projectName === 'dogma') {
    return null;
  }
  return (
    <WithProjectRole projectName={projectName} roles={['OWNER', 'MEMBER']}>
      {() => (
        <MetadataButton
          href={`/app/projects/${projectName}/settings`}
          props={{ size: 'sm' }}
          text={'Project Settings'}
        />
      )}
    </WithProjectRole>
  );
};
