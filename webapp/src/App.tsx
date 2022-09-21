import * as React from "react"
import {Box, ChakraProvider, theme,} from "@chakra-ui/react"

export default function App() {
    return (
        <ChakraProvider theme={theme}>
            <Box textAlign="center" fontSize="xl">
                    Hello world!
            </Box>
        </ChakraProvider>
    )
}
