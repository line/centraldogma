import Head from 'next/head';
import { LoginForm } from 'dogma/features/auth/LoginForm';
import { useAppSelector } from 'dogma/store';
import { useEffect } from 'react';
import { useRouter } from 'next/router';

const LoginPage = () => {
  const router = useRouter();
  const user = useAppSelector((state) => state.auth.user);
  useEffect(() => {
    if (user) {
      router.push('/');
    }
  }, [router, user]);
  return (
    <>
      <Head>
        <link rel="icon" href="/static/favicon.ico" />
        <meta name="Central Dogma | Login" content="Login ..." />
      </Head>
      <LoginForm />
    </>
  );
};

export default LoginPage;
