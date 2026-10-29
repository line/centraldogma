import Head from 'next/head';
import { Projects } from 'dogma/features/project/Projects';
import { Heading } from '@chakra-ui/react';
import { PageTitle } from 'dogma/common/components/PageTitle';

const ProjectsPage = () => {
  return (
    <>
      <Head>
        <link rel="icon" href="/favicon.ico" />
      </Head>
      <PageTitle title="Projects" />
      <Heading mb={10}>Projects</Heading>
      <Projects />
    </>
  );
};

export default ProjectsPage;
