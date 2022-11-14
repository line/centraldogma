import type { AppProps } from 'next/app';
import { store } from 'dogma/store';
import { Provider } from 'react-redux';
import { ChakraProvider, theme } from '@chakra-ui/react';
import { Authorized } from 'dogma/features/auth/Authorized';
import { NextPage } from 'next';
import { ReactElement, ReactNode } from 'react';
import { useRouter } from 'next/router';
import { Layout } from 'dogma/common/components/Layout';

export const WEB_AUTH_LOGIN = '/web/auth/login';

export type NextPageWithLayout<P = {}, IP = P> = NextPage<P, IP> & {
  getLayout?: (page: ReactElement) => ReactNode;
};

type AppPropsWithLayout = AppProps & {
  Component: NextPageWithLayout;
};

function MyApp({ Component, pageProps }: AppPropsWithLayout) {
  const router = useRouter();
  const getLayout =
    router.pathname === WEB_AUTH_LOGIN
      ? (page: ReactElement) => page
      : (page: ReactElement) => <Layout>{page}</Layout>;
  return (
    <Provider store={store}>
      <ChakraProvider theme={theme}>
        <Authorized>{getLayout(<Component {...pageProps} />)}</Authorized>
      </ChakraProvider>
    </Provider>
  );
}

export default MyApp;
