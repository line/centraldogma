import { Badge } from '@chakra-ui/react';

export const UserRole = ({ user }: { user: string }) => {
  return <Badge colorScheme={user.toLowerCase() === 'owner' ? 'blue' : 'green'}>{user}</Badge>;
};
