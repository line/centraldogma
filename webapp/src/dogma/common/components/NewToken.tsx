import {
  Button,
  Popover,
  PopoverArrow,
  PopoverBody,
  PopoverCloseButton,
  PopoverContent,
  PopoverFooter,
  PopoverHeader,
  PopoverTrigger,
  Spacer,
} from '@chakra-ui/react';
import { IoMdArrowDropdown } from 'react-icons/io';

export const NewToken = () => {
  return (
    <Popover placement="bottom">
      <PopoverTrigger>
        <Button size="sm" mr={4} rightIcon={<IoMdArrowDropdown />}>
          New Token
        </Button>
      </PopoverTrigger>
      <PopoverContent minWidth="max-content">
        <PopoverHeader pt={4} fontWeight="bold" border={0} mb={3}>
          Create a new token
        </PopoverHeader>
        <PopoverArrow />
        <PopoverCloseButton />
        <PopoverBody minWidth="max-content">TODO</PopoverBody>
        <PopoverFooter border="0" display="flex" alignItems="center" justifyContent="space-between" pb={4}>
          <Spacer />
          <Button type="submit" colorScheme="teal" variant="ghost" loadingText="Creating">
            Create
          </Button>
        </PopoverFooter>
      </PopoverContent>
    </Popover>
  );
};
