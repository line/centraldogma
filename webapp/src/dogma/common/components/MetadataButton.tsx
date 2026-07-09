import { Button, ButtonProps } from '@chakra-ui/react';
import Link from 'next/link';
import { FcSettings } from 'react-icons/fc';

export const MetadataButton = ({
  href,
  text,
  props,
  isDisabled,
}: {
  href: string;
  text?: string;
  props?: ButtonProps;
  isDisabled?: boolean;
}) => {
  return (
    <Button
      // A disabled anchor is still clickable, so the link is dropped when disabled.
      {...(isDisabled ? {} : { as: Link, href })}
      isDisabled={isDisabled}
      colorScheme="teal"
      variant="outline"
      rightIcon={<FcSettings />}
      {...props}
    >
      {text ?? 'Metadata'}
    </Button>
  );
};
