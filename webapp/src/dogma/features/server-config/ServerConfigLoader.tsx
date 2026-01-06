import { useEffect } from 'react';
import { useAppDispatch, useAppSelector } from 'dogma/hooks';
import { setServerConfig } from './serverConfigSlice';

export const ServerConfigLoader = ({ children }: { children: React.ReactNode }) => {
  const dispatch = useAppDispatch();
  const isLoaded = useAppSelector((state) => state.serverConfig.isLoaded);

  useEffect(() => {
    if (isLoaded) {
      return;
    }

    fetch('/configs')
      .then((response) => response.json())
      .then((data) => {
        dispatch(setServerConfig({ mtlsEnabled: data.mtlsEnabled }));
      })
      .catch((error) => {
        console.error('Failed to load server config:', error);
        dispatch(setServerConfig({ mtlsEnabled: false }));
      });
  }, [dispatch, isLoaded]);

  return <>{children}</>;
};
