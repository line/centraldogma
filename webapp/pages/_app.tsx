import type { AppProps } from "next/app";
import { store } from 'dogma/store';
import { Provider } from 'react-redux';
import { ChakraProvider, theme } from '@chakra-ui/react';
import { Layout } from 'dogma/common/components/Layout'
import { getSessionId } from "dogma/features/auth/Authorized";
import { useEffect, useState } from "react";
import { UserContext } from "dogma/common/context/user";

function MyApp({ Component, pageProps }: AppProps) {
    const [id, setId] = useState(null);
    useEffect(() => {
        setId(getSessionId())
    }, []);
    if (pageProps.protected && !id) {
        return<h1>You dont have access...</h1>;
    }
    return (
        <UserContext.Provider value={id}>
            <Provider store={store}>
                <ChakraProvider theme={theme}>
                    <Layout>
                        <Component {...pageProps} />
                    </Layout>
                </ChakraProvider>
            </Provider>
        </UserContext.Provider>
    );
}

export default MyApp;
