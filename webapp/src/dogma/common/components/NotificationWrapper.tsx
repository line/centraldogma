import { ReactNode, useEffect } from 'react';
import { useToast } from '@chakra-ui/react';
import { useSelector } from 'react-redux';
import { Notification } from 'dogma/features/notification/notificationSlice';

// Uses a plain `useSelector` with an inline state type (rather than each app's typed `useAppSelector`) so the
// component stays decoupled from any single app's RootState; it only depends on the 'notification' slice.
export const NotificationWrapper = (props: { children: ReactNode }) => {
  const { title, description, type, containerStyle, timestamp } = useSelector(
    (state: { notification: Notification }) => state.notification,
  );
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
