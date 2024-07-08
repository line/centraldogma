import { ReactNode, useEffect } from 'react';
import { useToast } from '@chakra-ui/react';
import { useAppSelector } from 'dogma/hooks';

export const ErrorWrapper = (props: { children: ReactNode }) => {
  const { title, text, type } = useAppSelector((state) => state.message);
  const toast = useToast();
  useEffect(() => {
    if (text) {
      toast({
        title: title,
        description: text,
        status: type,
        duration: 10000,
        isClosable: true,
      });
    }
  }, [title, text, type, toast]);
  return <>{props.children}</>;
};
