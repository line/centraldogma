import { Badge } from '@chakra-ui/react';

export const UserRole = ({ role }: { role: string }) => {
  return (
    <Badge colorScheme={role.toLowerCase() === 'user' || role.toLowerCase() === 'member' ? 'green' : 'blue'}>
      {role}
    </Badge>
  );
};
