import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

// The order mirrors the toctree of the Sphinx site. The restructured information
// architecture lands page by page once the content is rewritten.
const sidebars: SidebarsConfig = {
  docsSidebar: [
    'index',
    {
      type: 'category',
      label: 'Setting up',
      link: { type: 'generated-index', title: 'Setting up' },
      items: ['setup-installation', 'setup-configuration'],
    },
    'concepts',
    'client-cli',
    'client-java',
    'templates-variables',
    'mirroring',
    'xds',
    'auth',
    'known-issues',
  ],
};

export default sidebars;
