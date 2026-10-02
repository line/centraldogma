import { themes as prismThemes } from 'prism-react-renderer';
import remarkGithub from 'remark-github';
import type { Config } from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const config: Config = {
  title: 'Central Dogma',
  tagline:
    'Highly-available version-controlled service configuration repository',
  favicon: 'img/favicon.ico',

  url: 'https://line.github.io',
  // Central Dogma is served under a project path, unlike Armeria which has its own domain.
  baseUrl: '/centraldogma/',

  organizationName: 'line',
  projectName: 'centraldogma',

  onBrokenLinks: 'warn',

  markdown: {
    mermaid: true,
    hooks: {
      onBrokenMarkdownImages: 'warn',
      onBrokenMarkdownLinks: 'warn',
    },
  },

  themes: ['@docusaurus/theme-mermaid'],

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          path: 'src/content/docs',
          editUrl: 'https://github.com/line/centraldogma/edit/main/site-new/',
          remarkPlugins: [remarkGithub],
        },
        blog: false,
        theme: {
          customCss: ['./src/css/custom.css'],
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'Central Dogma',
      logo: {
        // Mark only. 'central_dogma.png' carries a white wordmark and is unusable on a light navbar.
        alt: 'Central Dogma',
        src: 'img/logo.png',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Documentation',
        },
        {
          // Replaced by an internal link once the release notes move into the site.
          href: 'https://github.com/line/centraldogma/releases',
          position: 'left',
          label: 'Release notes',
        },
        {
          href: 'https://github.com/line/centraldogma',
          position: 'right',
          label: 'GitHub',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [{ label: 'Documentation', to: '/docs' }],
        },
        {
          title: 'Community',
          items: [
            {
              label: 'Issues',
              href: 'https://github.com/line/centraldogma/issues',
            },
            { label: 'Discord', href: 'https://armeria.dev/s/discord' },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'Release notes',
              href: 'https://github.com/line/centraldogma/releases',
            },
            {
              label: 'Contributing',
              href: 'https://github.com/line/centraldogma/blob/main/CONTRIBUTING.md',
            },
            {
              label: 'License',
              href: 'https://github.com/line/centraldogma/blob/main/LICENSE.txt',
            },
          ],
        },
      ],
      copyright: `© 2017-${new Date().getFullYear()} LY Corporation`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['bash', 'groovy', 'java', 'json5', 'shell-session'],
    },
    docs: {
      sidebar: {
        hideable: true,
        autoCollapseCategories: true,
      },
    },
  } satisfies Preset.ThemeConfig,

  future: {
    v4: true,
    faster: true,
  },
};

export default config;
