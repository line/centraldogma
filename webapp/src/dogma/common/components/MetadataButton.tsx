import { Button, ButtonProps } from '@chakra-ui/react';
import Link from 'next/link';
import { FcSettings } from 'react-icons/fc';

export const MetadataButton = ({ href, text, props }: { href: string; text?: string; props?: ButtonProps }) => {
  return (
    <Button as={Link} href={href} colorScheme="teal" variant="outline" rightIcon={<FcSettings />} {...props}>
      {text ?? 'Metadata'}
    </Button>
  );
};
