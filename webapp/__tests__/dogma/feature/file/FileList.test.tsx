import { fireEvent, render } from '@testing-library/react';
import FileList, { FileListProps } from 'dogma/features/file/FileList';
import { FileDto } from 'dogma/features/file/FileDto';

describe('FileList', () => {
  let expectedProps: JSX.IntrinsicAttributes & FileListProps<object>;

  beforeEach(() => {
    const mockfileList = [
      {
        revision: 6,
        path: '/123456',
        type: 'TEXT',
        url: '/api/v1/projects/Gamma/repos/repo1/contents/123456',
      },
      { revision: 6, path: '/abc', type: 'TEXT', url: '/api/v1/projects/Gamma/repos/repo1/contents/abc' },
      {
        revision: 6,
        path: '/uuuuu',
        type: 'TEXT',
        url: '/api/v1/projects/Gamma/repos/repo1/contents/uuuuu',
      },
      {
        revision: 6,
        path: '/yyyyyyyy',
        type: 'TEXT',
        url: '/api/v1/projects/Gamma/repos/repo1/contents/yyyyyyyy',
      },
      {
        revision: 6,
        path: '/zzzzz',
        type: 'TEXT',
        url: '/api/v1/projects/Gamma/repos/repo1/contents/zzzzz',
      },
    ];
    expectedProps = {
      data: mockfileList,
      projectName: 'ProjectAlpha',
      repoName: 'repo1',
      handleCopyApiUrl: jest.fn(),
      handleCopyAsCurlCommand: jest.fn(),
    };
  });

  it('renders the file paths', () => {
    const { getByText } = render(<FileList {...expectedProps} />);
    let name;
    expectedProps.data.forEach((file: FileDto) => {
      name = getByText(file.path);
      expect(name).toBeVisible();
    });
  });

  it('renders a table with a row for each file', () => {
    const { container } = render(<FileList {...expectedProps} />);
    expect(container.querySelector('tbody').children.length).toBe(5);
  });

  it('has `${projectName}/repos/${repoName}/files/head{fileName}` on the view icon', () => {
    const { container } = render(<FileList {...expectedProps} />);
    const actionCell = container.querySelector('tbody').firstChild.firstChild.lastChild;
    const firstFileName = '/123456';
    expect(actionCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/files/head${firstFileName}`,
    );
  });

  it('has `${projectName}/repos/${repoName}/files/head{fileName}` on the file path cell', () => {
    const { container } = render(<FileList {...expectedProps} />);
    const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
    const firstFileName = '/123456';
    expect(firstCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/files/head${firstFileName}`,
    );
  });

  it('calls handleCopyApiUrl when copy API URL button is clicked', () => {
    const { getAllByText } = render(<FileList {...expectedProps} />);
    const firstButton = getAllByText('Copy API URL', { selector: 'button' })[0];
    fireEvent.click(firstButton);
    expect(expectedProps.handleCopyApiUrl).toHaveBeenCalledTimes(1);
  });

  it('calls handleCopyAsCurlCommand when copy as a curl command button is clicked', () => {
    const { getAllByText } = render(<FileList {...expectedProps} />);
    const firstButton = getAllByText('Copy as a curl command', { selector: 'button' })[0];
    fireEvent.click(firstButton);
    expect(expectedProps.handleCopyAsCurlCommand).toHaveBeenCalledTimes(1);
  });
});
