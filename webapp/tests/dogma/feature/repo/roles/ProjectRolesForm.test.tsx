import { renderWithProviders } from 'dogma/util/test-utils';
import { ProjectRolesForm } from 'dogma/features/repo/roles/ProjectRolesForm';

describe('ProjectRolesForm', () => {
  it('shows a private repository with Private and Public choices', () => {
    const { getByText, getAllByText, getByRole } = renderWithProviders(
      <ProjectRolesForm
        projectName="foo"
        repoName="bar"
        projectRoles={{ member: 'WRITE', guest: null }}
        allowPublicRepositories={true}
      />,
    );
    expect(getByText('Visibility')).toBeVisible();
    // Shown as both the current-state badge and the radio label.
    expect(getAllByText('Private').length).toBeGreaterThan(1);
    const publicRadio = getByRole('radio', { name: /Public/ });
    expect(publicRadio).not.toBeChecked();
    expect(publicRadio).toBeEnabled();
  });

  it('marks a guest READ repository as Public', () => {
    const { getByRole } = renderWithProviders(
      <ProjectRolesForm
        projectName="foo"
        repoName="bar"
        projectRoles={{ member: 'WRITE', guest: 'READ' }}
        allowPublicRepositories={true}
      />,
    );
    expect(getByRole('radio', { name: /Public/ })).toBeChecked();
  });

  it('disables the Public option when the project disallows public repositories', () => {
    const { getByRole } = renderWithProviders(
      <ProjectRolesForm
        projectName="foo"
        repoName="bar"
        projectRoles={{ member: 'WRITE', guest: null }}
        allowPublicRepositories={false}
      />,
    );
    expect(getByRole('radio', { name: /Public/ })).toBeDisabled();
  });

  it('keeps the Public option enabled for a legacy public repository in a disallowing project', () => {
    // The owner must still be able to switch such a repository to private.
    const { getByRole } = renderWithProviders(
      <ProjectRolesForm
        projectName="foo"
        repoName="bar"
        projectRoles={{ member: 'WRITE', guest: 'READ' }}
        allowPublicRepositories={false}
      />,
    );
    const publicRadio = getByRole('radio', { name: /Public/ });
    expect(publicRadio).toBeChecked();
    expect(publicRadio).toBeEnabled();
    expect(getByRole('radio', { name: /Private/ })).toBeEnabled();
  });
});
