import { ReactNode, useEffect } from 'react';
import { Box, useToast } from '@chakra-ui/react';
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
        // A long error message would otherwise grow the toast past the viewport, hiding the close button.
        description: description ? (
          <Box maxHeight="40vh" overflowY="auto" overflowWrap="anywhere" whiteSpace="pre-wrap">
            {description}
          </Box>
        ) : null,
        status: type,
        duration: 10000,
        isClosable: true,
        containerStyle,
      });
    }
  }, [title, description, type, timestamp, containerStyle, toast]);
  return <>{props.children}</>;
};
