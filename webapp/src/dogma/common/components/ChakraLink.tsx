import { Link, LinkProps, OmitCommonProps } from '@chakra-ui/react';
import NextLink from 'next/link';
import { DetailedHTMLProps, AnchorHTMLAttributes } from 'react';

export const ChakraLink = (
  props: JSX.IntrinsicAttributes &
    OmitCommonProps<
      DetailedHTMLProps<AnchorHTMLAttributes<HTMLAnchorElement>, HTMLAnchorElement>,
      keyof LinkProps
    > &
    LinkProps & { as?: 'a' },
) => {
  return <Link as={NextLink} {...props}></Link>;
};
