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
import { createMessage } from 'dogma/features/message/messageSlice';
import { useAppDispatch } from 'dogma/store';
import { MdArrowDropDown, MdContentCopy } from 'react-icons/md';

export const SecretWrapper = ({ appId, secret }: { appId: string; secret: string }) => {
  const dispatch = useAppDispatch();
  return (
    <Popover placement="bottom">
      <PopoverTrigger>
        <HStack>
          <Text fontWeight="bold">{appId}</Text>
          <IconButton aria-label="View token" icon={<MdArrowDropDown />} variant="ghost" />
        </HStack>
      </PopoverTrigger>
      <PopoverContent minWidth="max-content">
        <PopoverHeader pt={4} fontWeight="bold" border={0}>
          Token
        </PopoverHeader>
        <PopoverArrow />
        <PopoverCloseButton />
        <PopoverBody>
          <HStack>
            <Text>{secret}</Text>
            <IconButton
              aria-label="Copy to clipboard"
              icon={<MdContentCopy />}
              variant="ghost"
              onClick={async () => {
                await navigator.clipboard.writeText(secret);
                dispatch(createMessage({ title: '', text: 'copied to clipboard', type: 'success' }));
              }}
            />
          </HStack>
        </PopoverBody>
      </PopoverContent>
    </Popover>
  );
};
