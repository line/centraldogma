import type { AppProps } from "next/app";
import { store } from 'dogma/store';
import { Provider } from 'react-redux';
import { ChakraProvider, theme } from '@chakra-ui/react';
import { Layout } from 'dogma/common/components/Layout'

function MyApp({ Component, pageProps }: AppProps) {
    return (
        <Provider store={store}>
            <ChakraProvider theme={theme}>
                <Layout>
                    <Component {...pageProps} />
                </Layout>
            </ChakraProvider>
        </Provider>
    );
}

export default MyApp;
