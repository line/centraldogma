import { Badge } from '@chakra-ui/react';

function badgeColor(role: string) {
  switch (role.toLowerCase()) {
    case 'user':
    case 'member':
      return 'green';
    case 'owner':
    case 'admin':
      return 'blue';
    default:
      return 'gray';
  }
}

export const UserRole = ({ role }: { role: string }) => {
  return <Badge colorScheme={badgeColor(role)}>{role}</Badge>;
};
