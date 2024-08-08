'use client';
import { useRef } from 'react';
import { Provider } from 'react-redux';
import { AppStore, setupStore } from 'dogma/store';
import { setupListeners } from '@reduxjs/toolkit/query';

export default function StoreProvider({ children }: { children: React.ReactNode }) {
  const storeRef = useRef<AppStore>();
  if (!storeRef.current) {
    // Create the store instance the first time this renders
    const store = setupStore();
    storeRef.current = store;
    setupListeners(store.dispatch);
  }

  return <Provider store={storeRef.current}>{children}</Provider>;
}
