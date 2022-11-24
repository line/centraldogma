import type { AppProps } from 'next/app';
import { store } from 'dogma/store';
import { Provider } from 'react-redux';
import { ChakraProvider, theme } from '@chakra-ui/react';
import { Authorized } from 'dogma/features/auth/Authorized';
import { NextPage } from 'next';
import { ReactElement, ReactNode } from 'react';
import { useRouter } from 'next/router';
import { Layout } from 'dogma/common/components/Layout';
import { WEB_AUTH_LOGIN } from 'dogma/features/auth/util';
import { Deferred } from 'dogma/common/components/Deferred';

export type NextPageWithLayout<P = {}, IP = P> = NextPage<P, IP> & {
  getLayout?: (page: ReactElement) => ReactNode;
};

type AppPropsWithLayout = AppProps & {
  Component: NextPageWithLayout;
};

const MyApp = ({ Component, pageProps }: AppPropsWithLayout) => {
  const router = useRouter();
  const getLayout =
    router.pathname === WEB_AUTH_LOGIN
      ? (page: ReactElement) => page
      : (page: ReactElement) => <Layout>{page}</Layout>;
  return (
    <Provider store={store}>
      <ChakraProvider theme={theme}>
        <Deferred>
          <Authorized>{getLayout(<Component {...pageProps} />)}</Authorized>
        </Deferred>
      </ChakraProvider>
    </Provider>
  );
};

export default MyApp;
