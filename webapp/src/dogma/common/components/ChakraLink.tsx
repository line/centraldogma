import { Link, LinkProps } from '@chakra-ui/react';
import NextLink from 'next/link';

export const ChakraLink = (props: LinkProps) => {
  return <Link as={NextLink} {...props}></Link>;
};
