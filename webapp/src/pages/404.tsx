import { useColorMode } from '@chakra-ui/react';
import Error from 'next/error';

const FourOhFour = () => {
  const { colorMode } = useColorMode();
  return <Error statusCode={404} withDarkMode={colorMode === 'dark'} />;
};

export default FourOhFour;
