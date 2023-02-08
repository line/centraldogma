import { fireEvent, render } from '@testing-library/react';
import FileList, { FileListProps } from 'dogma/features/file/FileList';
import { FileDto } from 'dogma/features/file/FileDto';
import { CopySupport } from 'dogma/features/file/CopySupport';

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
        path: '/mydir',
        type: 'DIRECTORY',
        url: '/api/v1/projects/Gamma/repos/repo1/contents/mydir',
      },
    ];

    const mockCopySupport: CopySupport = {
      handleApiUrl: jest.fn(),
      handleWebUrl: jest.fn(),
      handleAsCliCommand: jest.fn(),
      handleAsCurlCommand: jest.fn(),
    };

    expectedProps = {
      data: mockfileList,
      projectName: 'ProjectAlpha',
      repoName: 'repo1',
      path: '',
      directoryPath: '/app/projects/ProjectAlpha/repos/repo1/list/head/',
      revision: 'head',
      copySupport: mockCopySupport,
    };
  });

  it('renders the file paths', () => {
    const { getByText } = render(<FileList {...expectedProps} />);
    let name;
    expectedProps.data.forEach((file: FileDto) => {
      name = getByText(file.path.slice(1));
      expect(name).toBeVisible();
    });
  });

  it('renders a table with a row for each file', () => {
    const { container } = render(<FileList {...expectedProps} />);
    expect(container.querySelector('tbody').children.length).toBe(5);
  });

  it('has `${projectName}/repos/${repoName}/files/head{fileName}` on the view icon when the type is a file', () => {
    const { container } = render(<FileList {...expectedProps} />);
    const actionCell = container.querySelector('tbody').firstChild.firstChild.lastChild;
    const firstFileName = '/123456';
    expect(actionCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/files/head${firstFileName}`,
    );
  });

  it('has `${directoryPath}${folderName.slice(1)}` on the view icon when the type is a directory', () => {
    const { container } = render(<FileList {...expectedProps} />);
    const actionCell = container.querySelector('tbody').lastChild.firstChild.lastChild;
    const folderName = '/mydir';
    expect(actionCell).toHaveAttribute('href', `${expectedProps.directoryPath}${folderName.slice(1)}`);
  });

  it('links to `${projectName}/repos/${repoName}/files/head{fileName}` when the type is non-directory', () => {
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
    const firstButton = getAllByText('API URL', { selector: 'button' })[0];
    fireEvent.click(firstButton);
    expect(expectedProps.copySupport.handleApiUrl).toHaveBeenCalledTimes(1);
  });

  it('calls handleCopyWebUrl when copy Web URL button is clicked', () => {
    const { getAllByText } = render(<FileList {...expectedProps} />);
    const firstButton = getAllByText('Web URL', { selector: 'button' })[0];
    fireEvent.click(firstButton);
    expect(expectedProps.copySupport.handleWebUrl).toHaveBeenCalledTimes(1);
  });

  it('calls handleCopyAsCurlCommand when copy as a CLI command button is clicked', () => {
    const { getAllByText } = render(<FileList {...expectedProps} />);
    const firstButton = getAllByText('CLI command', { selector: 'button' })[0];
    fireEvent.click(firstButton);
    expect(expectedProps.copySupport.handleAsCliCommand).toHaveBeenCalledTimes(1);
  });

  it('calls handleCopyAsCurlCommand when copy as a curl command button is clicked', () => {
    const { getAllByText } = render(<FileList {...expectedProps} />);
    const firstButton = getAllByText('cURL command', { selector: 'button' })[0];
    fireEvent.click(firstButton);
    expect(expectedProps.copySupport.handleAsCurlCommand).toHaveBeenCalledTimes(1);
  });

  it('links to `${directoryPath}${folderName.slice(1)}` when the type is a directory', () => {
    const { container } = render(<FileList {...expectedProps} />);
    const firstCell = container.querySelector('tbody').lastChild.firstChild.firstChild;
    const folderName = '/mydir';
    expect(firstCell).toHaveAttribute('href', `${expectedProps.directoryPath}${folderName.slice(1)}`);
  });

  it('calls handleCopyApiUrl when copy API URL button is clicked', () => {
    const { getAllByText } = render(<FileList {...expectedProps} />);
    const firstButton = getAllByText('API URL', { selector: 'button' })[0];
    fireEvent.click(firstButton);
    expect(expectedProps.copySupport.handleApiUrl).toHaveBeenCalledTimes(1);
  });

  it('calls handleCopyWebUrl when copy Web URL button is clicked', () => {
    const { getAllByText } = render(<FileList {...expectedProps} />);
    const firstButton = getAllByText('Web URL', { selector: 'button' })[0];
    fireEvent.click(firstButton);
    expect(expectedProps.copySupport.handleWebUrl).toHaveBeenCalledTimes(1);
  });

  it('calls handleCopyAsCurlCommand when copy as a CLI command button is clicked', () => {
    const { getAllByText } = render(<FileList {...expectedProps} />);
    const firstButton = getAllByText('CLI command', { selector: 'button' })[0];
    fireEvent.click(firstButton);
    expect(expectedProps.copySupport.handleAsCliCommand).toHaveBeenCalledTimes(1);
  });

  it('calls handleCopyAsCurlCommand when copy as a curl command button is clicked', () => {
    const { getAllByText } = render(<FileList {...expectedProps} />);
    const firstButton = getAllByText('cURL command', { selector: 'button' })[0];
    fireEvent.click(firstButton);
    expect(expectedProps.copySupport.handleAsCurlCommand).toHaveBeenCalledTimes(1);
  });

  it('links to `${directoryPath}${folderName.slice(1)}` when the type is a directory', () => {
    const { container } = render(<FileList {...expectedProps} />);
    const firstCell = container.querySelector('tbody').lastChild.firstChild.firstChild;
    const folderName = '/mydir';
    expect(firstCell).toHaveAttribute('href', `${expectedProps.directoryPath}${folderName.slice(1)}`);
  });
});
