import Head from 'next/head';
import { Projects } from 'dogma/features/project/Projects';

const Project = () => {
  return (
    <>
      <Head>
          <link rel="icon" href="/static/favicon.ico" />
          <meta
            name="Central Dogma | Project"
            content="Projects ... "
          />
      </Head>
      <Projects />
    </>
  )
};

export default Project;
