import Head from 'next/head';
import { Projects } from 'dogma/features/project/Projects';
import { GetStaticProps } from 'next';

const Project = () => {
  return (
    <>
      <Head>
          <link rel="icon" href="favicon.ico" />
          <meta
            name="Central Dogma"
            content="Keeping your config ... "
          />
      </Head>
      <Projects />
    </>
  )
};

export default Project;
