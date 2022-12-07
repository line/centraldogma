import Head from 'next/head';
import { LoginForm } from 'dogma/features/auth/LoginForm';
import { useAppDispatch, useAppSelector } from 'dogma/store';
import { useEffect } from 'react';
import Router from 'next/router';
import { login } from 'dogma/features/auth/authSlice';

const LoginPage = () => {
  const { isInAnonymousMode, sessionId } = useAppSelector((state) => state.auth);
  useEffect(() => {
    if (isInAnonymousMode || sessionId) {
      Router.replace('/');
    }
  }, [isInAnonymousMode, sessionId]);
  const dispatch = useAppDispatch();
  const submitForm = (data: { username: string; password: string }) => {
    dispatch(login({ username: data.username, password: data.password }));
  };
  return (
    <>
      <Head>
        <link rel="icon" href="/static/favicon.ico" />
        <meta name="Central Dogma | Login" content="Login ..." />
      </Head>
      <LoginForm handleSubmit={submitForm} />
    </>
  );
};

export default LoginPage;
