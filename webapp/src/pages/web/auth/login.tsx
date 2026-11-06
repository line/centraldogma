import Head from 'next/head';
import { LoginForm } from 'dogma/features/auth/LoginForm';
import { useEffect } from 'react';
import Router from 'next/router';
import { useAppSelector } from 'dogma/hooks';
import { PageTitle } from 'dogma/common/components/PageTitle';

const LoginPage = () => {
  const { isInAnonymousMode, user } = useAppSelector((state) => state.auth);
  useEffect(() => {
    if (isInAnonymousMode || user) {
      Router.replace('/');
    }
  }, [isInAnonymousMode, user]);
  return (
    <>
      <Head>
        <link rel="icon" href="/favicon.ico" />
      </Head>
      <PageTitle title="Login" />
      <LoginForm />
    </>
  );
};

export default LoginPage;
