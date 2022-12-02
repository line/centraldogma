import { render } from '@testing-library/react';
import FileList, { FileListProps } from 'dogma/features/file/FileList';
import { FileDto } from '../../../../src/dogma/features/file/FileDto';

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
    const { getByTestId } = render(<FileList {...expectedProps} />);
    expect(getByTestId('table-body').children.length).toBe(5);
  });

  it('generates `${projectName}/repos/${repoName}/files/head{fileName}` url when the view icon is clicked', () => {
    const { getByTestId } = render(<FileList {...expectedProps} />);
    const fileName = '/zzzzz';
    const repoViewLink = getByTestId(
      `/app/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/files/head-${fileName}`,
    );
    expect(repoViewLink).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/files/head${fileName}`,
    );
  });
});
