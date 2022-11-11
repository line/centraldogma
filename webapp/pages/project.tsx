import Head from 'next/head';
import { Projects } from 'dogma/features/project/Projects';
import { Layout } from 'dogma/common/components/Layout';
import { ReactElement } from 'react';

const Project = () => {
  return (
    <>
      <Head>
          <link rel="icon" href="favicon.ico" />
          <meta
            name="Central Dogma | Project"
            content="Projects ... "
          />
      </Head>
      <Projects />
    </>
  )
};

Project.getLayout = function getLayout(page: ReactElement) {
  return <Layout>{page}</Layout>;
}

export default Project;
