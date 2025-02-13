import { Box, Link, LinkProps } from '@chakra-ui/react';
import NextLink from 'next/link';
import React from 'react';

type ChakraLinkProps = LinkProps & {
  disabled?: boolean;
  href: string;
};
export const ChakraLink = React.forwardRef<HTMLAnchorElement, ChakraLinkProps>(
  ({ disabled, href, children, ...rest }, ref) => {
    if (disabled) {
      return <Box {...rest}>{children}</Box>;
    }
    return (
      <NextLink href={href} passHref legacyBehavior>
        <Link ref={ref} {...rest}>
          {children}
        </Link>
      </NextLink>
    );
  },
);

ChakraLink.displayName = 'ChakraLink';
