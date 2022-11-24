import { ReactNode, useEffect } from 'react';
import { useToast } from '@chakra-ui/react';
import { useAppSelector } from 'dogma/store';

export const ErrorWrapper = (props: { children: ReactNode }) => {
  const { errorText, errorType } = useAppSelector((state) => state.message);
  const toast = useToast();
  useEffect(() => {
    if (errorText) {
      toast({
        title: errorType,
        description: errorText,
        status: errorType,
        duration: 10000,
        isClosable: true,
      });
    }
  });
  return <>{props.children}</>;
};
