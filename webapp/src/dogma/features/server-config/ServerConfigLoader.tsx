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
      .then((response) => {
        if (!response.ok) {
          throw new Error(`Failed to load config: ${response.status}`);
        }
        return response.json();
      })
      .then((data) => {
        const mtlsEnabled = Boolean(data?.mtlsEnabled);
        dispatch(setServerConfig({ mtlsEnabled }));
      })
      .catch((error) => {
        console.error('Failed to load server config:', error);
        dispatch(setServerConfig({ mtlsEnabled: false }));
      });
  }, [dispatch, isLoaded]);

  return <>{children}</>;
};
