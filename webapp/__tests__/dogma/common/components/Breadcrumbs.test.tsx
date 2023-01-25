import { render } from '@testing-library/react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';

describe('Breadcrumbs', () => {
  it('renders the breadcrumb', () => {
    const { container } = render(<Breadcrumbs path={'/app/projects'} omitIndexList={[]} suffixes={{}} />);
    const crumb = container.querySelector('nav').firstChild.firstChild.firstChild;
    expect(crumb).toHaveTextContent('app');
  });

  it('does not render the omitted crumbs', () => {
    const { container } = render(<Breadcrumbs path={'/app/projects'} omitIndexList={[0]} suffixes={{}} />);
    const crumb = container.querySelector('nav').firstChild.firstChild.firstChild;
    expect(crumb).toHaveTextContent('projects');
  });

  it('adds suffix to the crumb link', () => {
    const { container } = render(
      <Breadcrumbs
        path={'/app/projects/my-project-name/repos/repo1'}
        omitIndexList={[]}
        suffixes={{ 2: '/list/head' }}
      />,
    );
    const crumb = container.querySelector('nav').firstChild.childNodes[2].firstChild;
    expect(crumb).toHaveTextContent('my-project-name');
    expect(crumb).toHaveAttribute('href', '/app/projects/my-project-name/list/head');
  });

  it('renders the directory link', () => {
    const { container } = render(
      <Breadcrumbs
        path={
          '/app/projects/my-project-name/repos/my-repo-name/files/head/folder/subfolder/subfolder2/my-file-name'
        }
        omitIndexList={[]}
        suffixes={{}}
      />,
    );
    const crumb = container.querySelector('nav').firstChild.childNodes[8].firstChild;
    expect(crumb).toHaveTextContent('subfolder');
    expect(crumb).toHaveAttribute(
      'href',
      '/app/projects/my-project-name/repos/my-repo-name/list/head/folder/subfolder',
    );
  });
});
