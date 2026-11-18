import { render, screen } from '@testing-library/react';
import { useRouter } from 'next/router';
import FileContentPage from 'pages/app/projects/[projectName]/repos/[repoName]/files/[revision]/[[...path]]';
import FileEditor from 'dogma/common/components/editor/FileEditor';

jest.mock('next/router', () => ({ useRouter: jest.fn() }));
jest.mock('dogma/features/api/apiSlice', () => ({
  useGetTitleQuery: () => ({ data: { title: 'Central Dogma' } }),
  useGetFileContentQuery: ({ filePath }: { filePath: string }) => ({
    data: { revision: 1, path: filePath, rawContent: '{}' },
    isLoading: false,
  }),
}));
jest.mock('dogma/common/components/editor/FileEditor', () => ({
  __esModule: true,
  default: jest.fn(() => null),
}));

describe('File content page', () => {
  it.each([
    { name: 'config.json', encoded: '%63onfig%2Ejson', extension: 'json' },
    { name: 'config.json', encoded: 'config.json?page=2#details', extension: 'json' },
    { name: 'nested-file.yaml', encoded: 'nested%2Dfile%2Eyaml', extension: 'yaml' },
  ])('uses the decoded route parameter for the file name $name at $encoded', ({ name, encoded, extension }) => {
    (useRouter as jest.Mock).mockReturnValue({
      query: { projectName: 'sample', repoName: 'example', revision: 'head', path: ['folder', name] },
      asPath: `/app/projects/sample/repos/example/files/head/folder/${encoded}`,
    });
    render(<FileContentPage />);
    expect(screen.getByRole('heading', { name })).toBeVisible();
    expect(FileEditor).toHaveBeenLastCalledWith(
      expect.objectContaining({ name, extension }),
      expect.anything(),
    );
  });
});
