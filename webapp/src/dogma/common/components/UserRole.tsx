import { Badge } from '@chakra-ui/react';

export const UserRole = ({ role }: { role: string }) => {
  return <Badge colorScheme={role.toLowerCase() === 'user' ? 'green' : 'blue'}>{role}</Badge>;
};
