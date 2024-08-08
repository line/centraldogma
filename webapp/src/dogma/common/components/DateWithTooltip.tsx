import { Badge, Tooltip } from '@chakra-ui/react';
import { format, formatDistance } from 'date-fns';

export const DateWithTooltip = ({ date }: { date: string }) => {
  return (
    <Tooltip label={format(new Date(date), 'dd MMM yyyy HH:mm z')}>
      <Badge>{formatDistance(new Date(date), new Date(), { addSuffix: true })}</Badge>
    </Tooltip>
  );
};
