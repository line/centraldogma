import Head from 'next/head';
import { Projects } from 'dogma/features/project/Projects';
import { Heading } from '@chakra-ui/react';

const ProjectsPage = () => {
  return (
    <>
      <Head>
        <link rel="icon" href="/favicon.ico" />
        <meta name="Central Dogma | Project" content="Projects ... " />
      </Head>
      <Heading mb={10}>Projects</Heading>
      <Projects />
    </>
  );
};

export default ProjectsPage;
