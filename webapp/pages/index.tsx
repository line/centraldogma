import React from 'react';
import Head from 'next/head';
import { LoginForm } from 'dogma/features/auth/LoginForm';

const HomePage = () => (
  <>
    <Head>
        <link rel="icon" href="favicon.ico" />
        <meta
          name="Central Dogma"
          content="Let's store your config in Central Dogma"
        />
    </Head>
    <LoginForm />
  </>
);

export default HomePage;
