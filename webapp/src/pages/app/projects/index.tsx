import Head from 'next/head';
import { Projects } from 'dogma/features/project/Projects';

const ProjectsPage = () => {
  return (
    <>
      <Head>
        <link rel="icon" href="/favicon.ico" />
        <meta name="Central Dogma | Project" content="Projects ... " />
      </Head>
      <Projects />
    </>
  );
};

export default ProjectsPage;
