import { ReactNode, useEffect } from 'react';
import { useToast } from '@chakra-ui/react';
import { useAppSelector } from 'dogma/hooks';

export const NotificationWrapper = (props: { children: ReactNode }) => {
  const { title, text, type, timestamp } = useAppSelector((state) => state.notification);
  const toast = useToast();
  useEffect(() => {
    if (text) {
      toast({
        title: title,
        description: text,
        status: type,
        duration: 10000,
        isClosable: true,
        containerStyle: {
          maxWidth: '60%',
        },
      });
    }
  }, [title, text, type, timestamp, toast]);
  return <>{props.children}</>;
};
