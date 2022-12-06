import {
  DrawerContent,
  DrawerCloseButton,
  DrawerHeader,
  DrawerBody,
  Button,
  DrawerFooter,
  Input,
  Textarea,
  Text,
  Divider,
  Box,
} from '@chakra-ui/react';

export const NewFileForm = () => {
  return (
    <DrawerContent>
      <DrawerCloseButton />
      <DrawerHeader>New File</DrawerHeader>
      <DrawerBody>
        <form
          id="my-form"
          onSubmit={(e) => {
            e.preventDefault();
            console.log('submitted');
          }}
        >
          <Box p={4}>
            <Text>Path</Text>
            <Input
              name="path"
              placeholder="Type 1) a file name 2) a directory name and '/' key or 3) 'backspace' key to go one directory up."
            />
            <Text>Content</Text>
            <Textarea placeholder="Here is a sample placeholder" overflow="hidden" rows={20} height="auto" />
            <Divider />
            <Text>Summary</Text>
            <Input name="summary" placeholder="Add a new file" />
            <Text>Detail</Text>
            <Textarea />
          </Box>
        </form>
      </DrawerBody>
      <DrawerFooter>
        <Button type="submit" form="my-form" colorScheme="teal">
          Commit
        </Button>
      </DrawerFooter>
    </DrawerContent>
  );
};
