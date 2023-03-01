import type { AppProps } from 'next/app';
import { store } from 'dogma/store';
import { Provider } from 'react-redux';
import { ChakraProvider, theme } from '@chakra-ui/react';
import { Authorized } from 'dogma/features/auth/Authorized';
import { NextPage } from 'next';
import {ReactElement, ReactNode, useEffect} from 'react';
import Router, { useRouter } from 'next/router';
import { Layout } from 'dogma/common/components/Layout';
import { ErrorWrapper } from 'dogma/common/components/ErrorWrapper';
import dynamic from 'next/dynamic';

const WEB_AUTH_LOGIN = '/web/auth/login';

export type NextPageWithLayout = NextPage & {
  getLayout?: (page: ReactElement) => ReactNode;
};

type AppPropsWithLayout = AppProps & {
  Component: NextPageWithLayout;
};

const DogmaApp = ({Component, pageProps}: AppPropsWithLayout) => {
  const router = useRouter();
  useEffect(() => {
    // Path patterns are used folder structures in Next.js such as '/app/projects/[projectNames]'.
    // If '/app/projects/myProj' is requested to Central Dogma server, the server fails to find
    // 'app/projects/myProj/index.html' file and returns 'index.html' as a fallback and the landing page is
    // rendered instead. As a workaround, this triggers Next.js router to route to the desired page when a page
    // is loaded for the first time.
    if (router.asPath !== "/") {
      Router.push(router.asPath);
    }
  }, []);
  const getLayout =
    router.pathname === WEB_AUTH_LOGIN
      ? (page: ReactElement) => page
      : (page: ReactElement) => <Layout>{page}</Layout>;

  return (
    <Provider store={store}>
      <ChakraProvider theme={theme}>
        <ErrorWrapper>
          <Authorized>{getLayout(<Component {...pageProps} />)}</Authorized>
        </ErrorWrapper>
      </ChakraProvider>
    </Provider>
  );
};

export default dynamic(() => Promise.resolve(DogmaApp), {
  ssr: false,
});
