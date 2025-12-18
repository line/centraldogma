/*
 * Copyright 2022 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import { combineReducers, configureStore } from '@reduxjs/toolkit';
import { authReducer } from 'dogma/features/auth/authSlice';
import { apiSlice } from 'dogma/features/api/apiSlice';
import { notificationReducer } from 'dogma/features/notification/notificationSlice';
import { filterReducer } from 'dogma/features/filter/filterSlice';
import { serverConfigReducer } from 'dogma/features/server-config/serverConfigSlice';

const rootReducer = combineReducers({
  auth: authReducer,
  filter: filterReducer,
  notification: notificationReducer,
  serverConfig: serverConfigReducer,
  [apiSlice.reducerPath]: apiSlice.reducer,
});

export function setupStore(preloadedState?: Partial<RootState>) {
  return configureStore({
    reducer: rootReducer,
    preloadedState,
    // Adding the api middleware enables caching, invalidation, polling,
    // and other useful features of `rtk-query`.
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware({
        serializableCheck: false,
      }).concat(apiSlice.middleware),
  });
}

export type RootState = ReturnType<typeof rootReducer>;
export type AppStore = ReturnType<typeof setupStore>;
export type AppDispatch = AppStore['dispatch'];
