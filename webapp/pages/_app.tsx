import type { AppProps } from "next/app";
import { store } from 'dogma/store';
import { Provider } from 'react-redux';
import { ChakraProvider, theme } from '@chakra-ui/react';
import { Authorized } from "dogma/features/auth/Authorized";

function MyApp({ Component, pageProps }: AppProps) {
    return (
            <Provider store={store}>
                <ChakraProvider theme={theme}>
                    <Authorized>
                        <Component {...pageProps} />
                    </Authorized>
                </ChakraProvider>
            </Provider>
    );
}

export default MyApp;
