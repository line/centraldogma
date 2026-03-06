import {
  HStack,
  IconButton,
  Popover,
  PopoverArrow,
  PopoverBody,
  PopoverCloseButton,
  PopoverContent,
  PopoverHeader,
  PopoverTrigger,
  Text,
} from '@chakra-ui/react';
import { MdArrowDropDown } from 'react-icons/md';

export const CertificateWrapper = ({ appId, certificateId }: { appId: string; certificateId: string }) => {
  return (
    <Popover placement="bottom">
      <PopoverTrigger>
        <HStack>
          <Text fontWeight="bold">{appId}</Text>
          <IconButton aria-label="View certificate" icon={<MdArrowDropDown />} variant="ghost" />
        </HStack>
      </PopoverTrigger>
      <PopoverContent minWidth="max-content">
        <PopoverHeader pt={4} fontWeight="bold" border={0}>
          Certificate ID
        </PopoverHeader>
        <PopoverArrow />
        <PopoverCloseButton />
        <PopoverBody>
          <Text>{certificateId}</Text>
        </PopoverBody>
      </PopoverContent>
    </Popover>
  );
};
