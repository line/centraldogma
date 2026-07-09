import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from 'dogma/util/test-utils';
import { Projects } from 'dogma/features/project/Projects';
import { ProjectDto } from 'dogma/features/project/ProjectDto';
import { PROJECT_READ_ONLY_HINT } from 'dogma/features/repo/useReadOnly';
import {
  useGetMetadataByProjectNameQuery,
  useGetProjectsQuery,
  useRestoreProjectMutation,
} from 'dogma/features/api/apiSlice';

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  useGetProjectsQuery: jest.fn(),
  useGetMetadataByProjectNameQuery: jest.fn(),
  useRestoreProjectMutation: jest.fn(),
}));

jest.mock('next/router', () => ({
  useRouter: () => ({
    isReady: true,
    query: {},
    pathname: '/app/projects',
    asPath: '/app/projects',
    push: jest.fn(),
  }),
}));

const creator = { name: 'System', email: 'system@localhost.localdomain' };

const mockProjects: ProjectDto[] = [
  {
    name: 'locked-project',
    creator,
    url: '/api/v1/projects/locked-project',
    userRole: 'OWNER',
    createdAt: '2026-01-01T00:00:00Z',
    status: 'READ_ONLY',
  },
  {
    name: 'open-project',
    creator,
    url: '/api/v1/projects/open-project',
    userRole: 'OWNER',
    createdAt: '2026-01-02T00:00:00Z',
    status: 'WRITABLE',
  },
];

const preloadedState = {
  auth: {
    isInAnonymousMode: false,
    csrfToken: null,
    isLoading: false,
    user: {
      login: 'admin',
      name: 'Admin User',
      email: 'admin@example.com',
      roles: [],
      systemAdmin: false,
    },
  },
  filter: { projectFilter: 'ALL' as const, isInitialProjectFilter: false },
};

const rowOf = (projectName: string) => screen.getByText(projectName).closest('tr');

describe('Projects', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useGetMetadataByProjectNameQuery as jest.Mock).mockReturnValue({ data: undefined, isLoading: false });
    (useRestoreProjectMutation as jest.Mock).mockReturnValue([jest.fn(), { isLoading: false }]);
    (useGetProjectsQuery as jest.Mock).mockReturnValue({
      data: mockProjects,
      error: undefined,
      isLoading: false,
    });
  });

  const renderProjects = () => renderWithProviders(<Projects />, { preloadedState });

  it('tags a read-only project', () => {
    renderProjects();
    expect(within(rowOf('locked-project')).getByText('Read-only')).toBeInTheDocument();
  });

  it('tags a writable project', () => {
    renderProjects();
    expect(within(rowOf('open-project')).getByText('Writable')).toBeInTheDocument();
  });

  // A disabled anchor is still clickable, so the link has to be dropped rather than disabled.
  it('disables the project settings button of a read-only project and drops its link', () => {
    renderProjects();
    const button = within(rowOf('locked-project')).getByRole('button', { name: 'Project Settings' });
    expect(button).toBeDisabled();
    expect(button.closest('a')).toBeNull();
  });

  it('explains why the project settings button is disabled on hover', async () => {
    renderProjects();
    const button = within(rowOf('locked-project')).getByRole('button', { name: 'Project Settings' });

    await userEvent.hover(button.parentElement);

    expect(await screen.findByRole('tooltip')).toHaveTextContent(PROJECT_READ_ONLY_HINT);
  });

  it('keeps the project settings button of a writable project linked', () => {
    renderProjects();
    const button = within(rowOf('open-project')).getByRole('button', { name: 'Project Settings' });
    expect(button).toBeEnabled();
    expect(button.closest('a')).toHaveAttribute('href', '/app/projects/open-project/settings');
  });

  it('keeps a read-only project browsable', () => {
    renderProjects();
    expect(within(rowOf('locked-project')).getByRole('link', { name: 'locked-project' })).toHaveAttribute(
      'href',
      '/app/projects/locked-project',
    );
  });
});
