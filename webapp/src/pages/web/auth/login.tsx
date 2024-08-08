import Head from 'next/head';
import { LoginForm } from 'dogma/features/auth/LoginForm';
import { useEffect } from 'react';
import Router from 'next/router';
import { useAppSelector } from 'dogma/hooks';

const LoginPage = () => {
  const { isInAnonymousMode, sessionId } = useAppSelector((state) => state.auth);
  useEffect(() => {
    if (isInAnonymousMode || sessionId) {
      Router.replace('/');
    }
  }, [isInAnonymousMode, sessionId]);
  return (
    <>
      <Head>
        <link rel="icon" href="/favicon.ico" />
        <meta name="Central Dogma | Login" content="Login ..." />
      </Head>
      <LoginForm />
    </>
  );
};

export default LoginPage;
