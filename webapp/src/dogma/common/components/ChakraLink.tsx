import { Box, Link, LinkProps } from '@chakra-ui/react';
import NextLink from 'next/link';

type ChakraLinkProps = LinkProps & {
  disabled?: boolean;
};
export const ChakraLink = (props: ChakraLinkProps) => {
  if (props.disabled) {
    return <Box {...props}></Box>;
  }
  return <Link as={NextLink} {...props}></Link>;
};
