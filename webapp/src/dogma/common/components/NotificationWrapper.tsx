import { ReactNode, useEffect } from 'react';
import { useToast } from '@chakra-ui/react';
import { useAppSelector } from 'dogma/hooks';

export const NotificationWrapper = (props: { children: ReactNode }) => {
  const { title, description, type, containerStyle, timestamp } = useAppSelector((state) => state.notification);
  const toast = useToast();
  useEffect(() => {
    if (timestamp) {
      toast({
        title,
        description,
        status: type,
        duration: 10000,
        isClosable: true,
        containerStyle,
      });
    }
  }, [title, description, type, timestamp, containerStyle, toast]);
  return <>{props.children}</>;
};
