import { Box, HStack } from '@chakra-ui/react';
import { RiRobot2Line } from 'react-icons/ri';
import { FaRegUserCircle } from 'react-icons/fa';

type AuthorProps = {
  name: string;
};

export const Author = ({ name }: AuthorProps) => {
  return (
    <HStack>
      <Box>{name === 'System' ? <RiRobot2Line /> : <FaRegUserCircle />}</Box>
      <Box>{name}</Box>
    </HStack>
  );
};
