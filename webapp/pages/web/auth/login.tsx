import Head from 'next/head';
import { LoginForm } from 'dogma/features/auth/LoginForm';
import { useAppSelector } from 'dogma/store';
const LoginPage = () => {
  const user = useAppSelector((state) => state.auth.user);
  return (
    <>
      <Head>
        <link rel="icon" href="/static/favicon.ico" />
        <meta name="Central Dogma | Login" content="Login ..." />
      </Head>
      {user ? <h1>You are logged in.</h1> : <LoginForm />}
    </>
  );
};

export default LoginPage;
