import React, { type ReactNode } from 'react';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

// Placeholder landing page. The designed landing page lands with the theme work.
const Home: React.FC = (): ReactNode => {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout description={siteConfig.tagline}>
      <main className="container margin-vert--xl">
        <Heading as="h1">{siteConfig.title}</Heading>
        <p>{siteConfig.tagline}</p>
        <Link className="button button--primary button--lg" to="/docs">
          Read the documentation
        </Link>
      </main>
    </Layout>
  );
};

export default Home;
