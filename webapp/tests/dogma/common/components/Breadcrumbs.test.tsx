import { render, screen } from '@testing-library/react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';

describe('Breadcrumbs', () => {
  it.each(['@sample', 'part#one', 'key?value', 'a/b', 'a:b;c=d&x+y,z', '공백 이름', '%40literal'])(
    'decodes the label %s once without changing the link destination',
    (label) => {
      const encoded = encodeURIComponent(label);
      const { rerender } = render(<Breadcrumbs path={`/app/projects/${encoded}/settings`} />);
      expect(screen.getByRole('link', { name: label })).toHaveAttribute('href', `/app/projects/${encoded}`);
      rerender(<Breadcrumbs path={`/app/projects/${encoded}`} />);
      expect(screen.getByText(label)).toBeVisible();
    },
  );

  it.each(['?page=2', '#details', '?page=2#details'])(
    'excludes URL query and fragment %s from labels and ancestor links',
    (suffix) => {
      render(<Breadcrumbs path={`/app/projects/%40sample/repos/example${suffix}`} />);
      expect(screen.getByRole('link', { name: '@sample' })).toHaveAttribute('href', '/app/projects/%40sample');
      expect(screen.getByText('example')).toBeVisible();
      expect(screen.queryByText(`example${suffix}`)).not.toBeInTheDocument();
    },
  );

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
        suffixes={{ 2: '/tree/head' }}
      />,
    );
    const crumb = container.querySelector('nav').firstChild.childNodes[2].firstChild;
    expect(crumb).toHaveTextContent('my-project-name');
    expect(crumb).toHaveAttribute('href', '/app/projects/my-project-name/tree/head');
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
      '/app/projects/my-project-name/repos/my-repo-name/tree/head/folder/subfolder',
    );
  });
});
