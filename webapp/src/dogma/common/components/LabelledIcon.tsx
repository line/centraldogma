import { IconType } from 'react-icons';
import { Icon } from '@chakra-ui/react';

export const LabelledIcon = ({ icon, text, size }: { icon: IconType; text: string; size?: number }) => (
  <>
    <Icon as={icon} boxSize={size ?? 4} marginBottom="-3px" /> {text}
  </>
);
