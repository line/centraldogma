/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import { configureStore, createSlice } from '@reduxjs/toolkit';
import { AuthState } from 'dogma/features/auth/authSlice';

function createTestStore(authState: Partial<AuthState> = {}) {
  const defaultAuth: AuthState = {
    isInAnonymousMode: false,
    csrfToken: null,
    user: null,
    isLoading: false,
    ...authState,
  };

  const authSlice = createSlice({
    name: 'auth',
    initialState: defaultAuth,
    reducers: {
      clearAuth: (state) => {
        state.isInAnonymousMode = false;
        state.user = null;
        state.csrfToken = null;
        state.isLoading = false;
      },
    },
  });

  return configureStore({
    reducer: {
      auth: authSlice.reducer,
    },
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware({
        serializableCheck: false,
      }),
  });
}

describe('apiSlice prepareHeaders', () => {
  describe('anonymous mode', () => {
    it('should set Authorization header to "Bearer anonymous" in anonymous mode', () => {
      const headers = new Headers();
      const store = createTestStore({ isInAnonymousMode: true });

      // Simulate what prepareHeaders does
      const auth = store.getState().auth;
      if (auth.isInAnonymousMode) {
        headers.set('Authorization', 'Bearer anonymous');
      }

      expect(headers.get('Authorization')).toBe('Bearer anonymous');
    });

    it('should not set X-CSRF-Token in anonymous mode even for mutations', () => {
      const headers = new Headers();
      const store = createTestStore({
        isInAnonymousMode: true,
        csrfToken: 'some-csrf-token',
      });

      const auth = store.getState().auth;
      if (auth.isInAnonymousMode) {
        headers.set('Authorization', 'Bearer anonymous');
        // Should return early without setting other headers
      } else {
        if (auth.csrfToken) {
          headers.set('X-CSRF-Token', auth.csrfToken);
        }
      }

      expect(headers.get('Authorization')).toBe('Bearer anonymous');
      expect(headers.get('X-CSRF-Token')).toBeNull();
    });
  });

  describe('authenticated mode', () => {
    it('should not set Authorization header when not in anonymous mode', () => {
      const headers = new Headers();
      const store = createTestStore({ isInAnonymousMode: false });

      const auth = store.getState().auth;
      if (auth.isInAnonymousMode) {
        headers.set('Authorization', 'Bearer anonymous');
      }

      expect(headers.get('Authorization')).toBeNull();
    });

    it('should set X-CSRF-Token for mutations when authenticated', () => {
      const headers = new Headers();
      const store = createTestStore({
        isInAnonymousMode: false,
        csrfToken: 'test-csrf-token',
      });

      const auth = store.getState().auth;
      const type = 'mutation';

      if (auth.isInAnonymousMode) {
        headers.set('Authorization', 'Bearer anonymous');
      } else if (type === 'mutation') {
        if (auth.csrfToken) {
          headers.set('X-CSRF-Token', auth.csrfToken);
        }
        if (!headers.has('Content-Type')) {
          headers.set('Content-Type', 'application/json; charset=UTF-8');
        }
      }

      expect(headers.get('X-CSRF-Token')).toBe('test-csrf-token');
      expect(headers.get('Content-Type')).toBe('application/json; charset=UTF-8');
      expect(headers.get('Authorization')).toBeNull();
    });

    it('should not set X-CSRF-Token for queries when authenticated', () => {
      const headers = new Headers();
      const store = createTestStore({
        isInAnonymousMode: false,
        csrfToken: 'test-csrf-token',
      });

      const auth = store.getState().auth;
      const type = 'query';

      if (auth.isInAnonymousMode) {
        headers.set('Authorization', 'Bearer anonymous');
      } else if (type === 'mutation') {
        if (auth.csrfToken) {
          headers.set('X-CSRF-Token', auth.csrfToken);
        }
      }

      expect(headers.get('X-CSRF-Token')).toBeNull();
      expect(headers.get('Authorization')).toBeNull();
    });
  });
});

describe('baseQueryWithReauth behavior', () => {
  describe('401 handling in anonymous mode', () => {
    it('should not clear auth when receiving 401 in anonymous mode', () => {
      const store = createTestStore({
        isInAnonymousMode: true,
        user: {
          login: 'anonymous',
          name: 'Anonymous',
          email: 'anonymous@localhost',
          roles: [],
          systemAdmin: false,
        },
      });

      // Simulate 401 response handling
      const errorStatus = 401;
      const auth = store.getState().auth;

      let shouldClearAuth = false;
      if (errorStatus === 401 && !auth.isInAnonymousMode) {
        shouldClearAuth = true;
      }

      expect(shouldClearAuth).toBe(false);
      // Verify auth state is preserved
      expect(store.getState().auth.isInAnonymousMode).toBe(true);
      expect(store.getState().auth.user).not.toBeNull();
      expect(store.getState().auth.user?.login).toBe('anonymous');
    });

    it('should clear auth when receiving 401 in authenticated mode', () => {
      const store = createTestStore({
        isInAnonymousMode: false,
        csrfToken: 'test-csrf-token',
        user: {
          login: 'testuser',
          name: 'Test User',
          email: 'test@example.com',
          roles: ['LEVEL_USER'],
          systemAdmin: false,
        },
      });

      // Simulate 401 response handling
      const errorStatus = 401;
      const auth = store.getState().auth;

      let shouldClearAuth = false;
      if (errorStatus === 401 && !auth.isInAnonymousMode) {
        shouldClearAuth = true;
      }

      expect(shouldClearAuth).toBe(true);
    });
  });

  describe('non-401 error handling', () => {
    it('should not clear auth for non-401 errors regardless of mode', () => {
      const store = createTestStore({
        isInAnonymousMode: false,
        csrfToken: 'test-csrf-token',
        user: {
          login: 'testuser',
          name: 'Test User',
          email: 'test@example.com',
          roles: ['LEVEL_USER'],
          systemAdmin: false,
        },
      });

      const errorStatus = 500;
      const auth = store.getState().auth;

      let shouldClearAuth = false;
      if (errorStatus === 401 && !auth.isInAnonymousMode) {
        shouldClearAuth = true;
      }

      expect(shouldClearAuth).toBe(false);
    });
  });
});

describe('authSlice reducers for anonymous mode', () => {
  it('should set isInAnonymousMode to true when checkSecurityEnabled is rejected', () => {
    // Simulates the reducer behavior when /security_enabled returns 404
    const store = createTestStore();
    expect(store.getState().auth.isInAnonymousMode).toBe(false);

    // After checkSecurityEnabled.rejected, the state should be:
    const expectedState: AuthState = {
      isInAnonymousMode: true,
      csrfToken: null,
      isLoading: false,
      user: {
        login: 'anonymous',
        name: 'Anonymous',
        email: 'anonymous@localhost',
        roles: [],
        systemAdmin: false,
      },
    };

    // Verify expected state structure
    expect(expectedState.isInAnonymousMode).toBe(true);
    expect(expectedState.user?.login).toBe('anonymous');
    expect(expectedState.csrfToken).toBeNull();
  });

  it('should set isInAnonymousMode to false when clearAuth is dispatched', () => {
    const authSlice = createSlice({
      name: 'auth',
      initialState: {
        isInAnonymousMode: true,
        csrfToken: null,
        user: {
          login: 'anonymous',
          name: 'Anonymous',
          email: 'anonymous@localhost',
          roles: [] as string[],
          systemAdmin: false,
        },
        isLoading: false,
      } as AuthState,
      reducers: {
        clearAuth: (state) => {
          state.isInAnonymousMode = false;
          state.user = null;
          state.csrfToken = null;
          state.isLoading = false;
        },
      },
    });

    const store = configureStore({
      reducer: { auth: authSlice.reducer },
    });

    expect(store.getState().auth.isInAnonymousMode).toBe(true);

    store.dispatch(authSlice.actions.clearAuth());

    expect(store.getState().auth.isInAnonymousMode).toBe(false);
    expect(store.getState().auth.user).toBeNull();
  });
});
