import { useColorMode } from '@chakra-ui/react';
import Error from 'next/error';

const FourOhFour = (props: { title: string }) => {
  const { colorMode } = useColorMode();
  return <Error statusCode={404} withDarkMode={colorMode === 'dark'} title={props.title} />;
};

export default FourOhFour;
