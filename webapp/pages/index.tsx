import Head from 'next/head';
import { LoginForm } from 'dogma/features/auth/LoginForm';
const HomePage = () => (
  <>
    <Head>
        <link rel="icon" href="favicon.ico" />
        <meta
          name="Central Dogma | Login"
          content="Login ..."
        />
    </Head>
    <LoginForm />
  </>
);

export default HomePage;
