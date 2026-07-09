import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from 'dogma/util/test-utils';
import { NewRepo } from 'dogma/features/repo/NewRepo';
import { PROJECT_READ_ONLY_HINT } from 'dogma/features/repo/useReadOnly';
import {
  useAddNewRepoMutation,
  useGetMetadataByProjectNameQuery,
  useGetProjectsQuery,
} from 'dogma/features/api/apiSlice';

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  useAddNewRepoMutation: jest.fn(),
  useGetMetadataByProjectNameQuery: jest.fn(),
  useGetProjectsQuery: jest.fn(),
}));

jest.mock('next/router', () => ({
  __esModule: true,
  default: { push: jest.fn() },
}));

const authState = {
  isInAnonymousMode: false,
  csrfToken: null,
  isLoading: false,
  user: {
    login: 'admin',
    name: 'Admin User',
    email: 'admin@example.com',
    roles: [],
    systemAdmin: true,
  },
};

const renderNewRepo = (projectStatus: 'WRITABLE' | 'READ_ONLY') => {
  (useGetProjectsQuery as jest.Mock).mockReturnValue({ data: [{ name: 'foo', status: projectStatus }] });
  return renderWithProviders(<NewRepo projectName="foo" />, { preloadedState: { auth: authState } });
};

describe('NewRepo', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAddNewRepoMutation as jest.Mock).mockReturnValue([jest.fn(), { isLoading: false }]);
    // A system administrator is an OWNER of every project, but only once the metadata has arrived.
    (useGetMetadataByProjectNameQuery as jest.Mock).mockReturnValue({ data: { members: {} } });
  });

  it('opens the create form when the project is writable', async () => {
    renderNewRepo('WRITABLE');
    const button = screen.getByRole('button', { name: 'New Repository' });
    expect(button).toBeEnabled();

    await userEvent.click(button);
    expect(await screen.findByPlaceholderText('my-repo-name')).toBeInTheDocument();
  });

  it('disables the button when the project is read-only', () => {
    renderNewRepo('READ_ONLY');
    expect(screen.getByRole('button', { name: 'New Repository' })).toBeDisabled();
  });

  // A disabled button swallows pointer events, so the hint has to live on a wrapper around it.
  it('explains why the button is disabled on hover', async () => {
    renderNewRepo('READ_ONLY');
    const button = screen.getByRole('button', { name: 'New Repository' });

    await userEvent.hover(button.parentElement);

    expect(await screen.findByRole('tooltip')).toHaveTextContent(PROJECT_READ_ONLY_HINT);
  });

  it('does not open the create form when the project is read-only', async () => {
    renderNewRepo('READ_ONLY');

    await userEvent.click(screen.getByRole('button', { name: 'New Repository' }));

    expect(screen.queryByPlaceholderText('my-repo-name')).not.toBeInTheDocument();
  });
});
