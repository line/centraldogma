import type { AppProps } from 'next/app';
import { store } from 'dogma/store';
import { Provider } from 'react-redux';
import { ChakraProvider, theme } from '@chakra-ui/react';
import { Authorized } from 'dogma/features/auth/Authorized';
import { NextPage } from 'next';
import { ReactElement, ReactNode } from 'react';
import { useRouter } from 'next/router';
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

let urlRewrite = false;
const DogmaApp = ({ Component, pageProps }: AppPropsWithLayout) => {
  const router = useRouter();
  if (!urlRewrite) {
    // Next.js uses a path pattern to dynamically match the request path to a specific HTML file.
    // For example, `/app/projects/[projectNames]/index.html' is generated to render `/app/projects/myProj`
    // page. `FileService` serving the static resources for Central Dogma webapp does not understand the
    // path pattern syntax in the folder names. If `/app/projects/myProj` is requested, the `FileService` will
    // try to find '/app/projects/myProj/index.html' as is and fails, which in turn 'index.html' is returned as
    // a fallback. As a workaround, this triggers Next.js router to route to the desired page when a page is
    // loaded for the first time.
    if (router.asPath !== '/' && router.isReady) {
      router.replace(router.asPath);
      urlRewrite = true;
    }
  }

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
