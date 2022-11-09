import Head from 'next/head';
import { Projects } from 'dogma/features/project/Projects';

const HomePage = () => {
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

export default HomePage;
