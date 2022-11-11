import Head from 'next/head';
import { LoginForm } from 'dogma/features/auth/LoginForm';
import { useAppSelector } from 'dogma/store';
const HomePage = () => {
  const user = useAppSelector((state) => state.auth.user);
  return (
  <>
    <Head>
        <link rel="icon" href="favicon.ico" />
        <meta
          name="Central Dogma | Login"
          content="Login ..."
        />
    </Head>
    {user ? <h1>You've logged in.</h1> : <LoginForm />}
  </>
)};

export default HomePage;
