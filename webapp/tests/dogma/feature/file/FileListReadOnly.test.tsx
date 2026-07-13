import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from 'dogma/util/test-utils';
import FileList from 'dogma/features/file/FileList';
import { CopySupport } from 'dogma/features/file/CopySupport';
import { PROJECT_READ_ONLY_HINT, REPO_READ_ONLY_HINT } from 'dogma/features/repo/useReadOnly';
import { useGetProjectsQuery, useGetReposQuery } from 'dogma/features/api/apiSlice';

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  useGetProjectsQuery: jest.fn(),
  useGetReposQuery: jest.fn(),
}));

const copySupport: CopySupport = {
  handleApiUrl: jest.fn(),
  handleWebUrl: jest.fn(),
  handleAsCliCommand: jest.fn(),
  handleAsCurlCommand: jest.fn(),
};

const data = [
  { revision: 6, path: '/hello.txt', type: 'TEXT', url: '/api/v1/projects/foo/repos/bar/contents/hello.txt' },
];

const renderFileList = (projectStatus: string, repoStatus: string) => {
  (useGetProjectsQuery as jest.Mock).mockReturnValue({ data: [{ name: 'foo', status: projectStatus }] });
  (useGetReposQuery as jest.Mock).mockReturnValue({ data: [{ name: 'bar', status: repoStatus }] });
  return renderWithProviders(
    <FileList
      data={data}
      projectName="foo"
      repoName="bar"
      path=""
      directoryPath="/app/projects/foo/repos/bar/tree/head"
      revision="head"
      copySupport={copySupport}
    />,
  );
};

const deleteButton = () => screen.getByRole('button', { name: 'Delete' });

describe('FileList delete action', () => {
  beforeEach(() => jest.clearAllMocks());

  it('offers Delete on a writable repository', () => {
    renderFileList('WRITABLE', 'WRITABLE');
    expect(deleteButton()).toBeEnabled();
  });

  it('disables Delete when the repository is read-only', async () => {
    renderFileList('WRITABLE', 'READ_ONLY');
    expect(deleteButton()).toBeDisabled();

    await userEvent.hover(deleteButton().parentElement);
    expect(await screen.findByRole('tooltip')).toHaveTextContent(REPO_READ_ONLY_HINT);
  });

  it('disables Delete when the whole project is read-only', async () => {
    renderFileList('READ_ONLY', 'READ_ONLY');
    expect(deleteButton()).toBeDisabled();

    await userEvent.hover(deleteButton().parentElement);
    expect(await screen.findByRole('tooltip')).toHaveTextContent(PROJECT_READ_ONLY_HINT);
  });

  it('does not open the delete confirmation while read-only', async () => {
    renderFileList('WRITABLE', 'READ_ONLY');

    await userEvent.click(deleteButton());

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('keeps the copy actions available while read-only', () => {
    renderFileList('WRITABLE', 'READ_ONLY');
    const row = screen.getByText('hello.txt').closest('tr');
    expect(within(row).getByRole('button', { name: /Copy/ })).toBeEnabled();
  });
});
