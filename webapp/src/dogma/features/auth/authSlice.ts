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

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { UserDto } from 'dogma/features/auth/UserDto';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { newNotification } from 'dogma/features/notification/notificationSlice';

const baseUrl = process.env.NEXT_PUBLIC_HOST || '';

const getCsrfTokenFromMeta = (): string | null => {
  if (typeof window === 'undefined') {
    return null;
  }
  const element = document.querySelector('meta[name="csrf-token"]');
  return element?.getAttribute('content') ?? null;
};

const updateCsrfTokenInMeta = (token: string) => {
  if (typeof window === 'undefined' || !token) {
    return;
  }
  const element = document.querySelector('meta[name="csrf-token"]');
  if (element) {
    element.setAttribute('content', token);
  }
};

export const getUser = createAsyncThunk('/auth/user', async (_, { dispatch, rejectWithValue }) => {
  try {
    const response = await fetch(`${baseUrl}/api/v0/users/me`, { credentials: 'include' });
    if (!response.ok) {
      const data = await response.json().catch(() => ({ message: response.statusText }));
      throw { response: { data, status: response.status }, message: data.message || response.statusText };
    }
    const csrfTokenFromHeader = response.headers.get('x-csrf-token');
    const data = await response.json();
    return {
      user: data as UserDto,
      csrfToken: csrfTokenFromHeader,
    };
  } catch (err) {
    const error: string = ErrorMessageParser.parse(err);
    // Don't show "Unauthorized" notification on the login page — it's expected.
    if (!err.response || err.response.status !== 401) {
      dispatch(newNotification('', error, 'error'));
    }
    return rejectWithValue(error);
  }
});

export interface LoginParams {
  username: string;
  password: string;
}

export const login = createAsyncThunk(
  '/auth/login',
  async (params: LoginParams, { dispatch, rejectWithValue }) => {
    try {
      const response = await fetch(`${baseUrl}/api/v1/login`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: new URLSearchParams({ username: params.username, password: params.password }),
      });
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({ message: response.statusText }));
        throw { response: { data: errorData, status: response.status }, message: errorData.message || response.statusText };
      }
      const data = await response.json();
      updateCsrfTokenInMeta(data.csrf_token);
      return data;
    } catch (err) {
      const error: string = ErrorMessageParser.parse(err);
      dispatch(newNotification('', error, 'error'));
      return rejectWithValue(error);
    }
  },
);

export const checkSecurityEnabled = createAsyncThunk(
  '/auth/securityEnabled',
  async (_, { dispatch, rejectWithValue }) => {
    try {
      const response = await fetch(`${baseUrl}/security_enabled`, { credentials: 'include' });
      if (!response.ok) {
        const data = await response.json().catch(() => ({ message: response.statusText }));
        throw { response: { data, status: response.status }, message: data.message || response.statusText };
      }
    } catch (err) {
      const error: string = ErrorMessageParser.parse(err);
      if (err.response && err.response.status === 404) {
        dispatch(newNotification('', 'Accessing Central Dogma in anonymous mode', 'info'));
      }

      return rejectWithValue(error);
    }
  },
);

export const logout = createAsyncThunk('/auth/logout', async (_, { getState, dispatch, rejectWithValue }) => {
  try {
    const { auth } = getState() as { auth: AuthState };
    const response = await fetch(`${baseUrl}/api/v1/logout`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'X-CSRF-Token': auth.csrfToken,
      },
    });
    if (!response.ok) {
      const data = await response.json().catch(() => ({ message: response.statusText }));
      throw { response: { data, status: response.status }, message: data.message || response.statusText };
    }
  } catch (err) {
    const error: string = ErrorMessageParser.parse(err);
    dispatch(newNotification('', error, 'error'));
    return rejectWithValue(error);
  }
});

export interface AuthState {
  isInAnonymousMode: boolean;
  csrfToken: string | null;
  user: UserDto | null;
  isLoading: boolean;
}

const initialState: AuthState = {
  isInAnonymousMode: false,
  csrfToken: getCsrfTokenFromMeta(),
  user: null,
  isLoading: true,
};

const anonymousUser: UserDto = {
  login: 'anonymous',
  name: 'Anonymous',
  email: 'anonymous@localhost',
  roles: [],
  systemAdmin: false,
};

export const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    clearAuth: (state) => {
      state.isInAnonymousMode = false;
      state.user = null;
      state.csrfToken = null;
      state.isLoading = false;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(checkSecurityEnabled.rejected, (state) => {
        state.isInAnonymousMode = true;
        state.csrfToken = null;
        state.isLoading = false;
        state.user = anonymousUser;
      })
      .addCase(login.fulfilled, (state, { payload }) => {
        state.csrfToken = payload.csrf_token;
      })
      .addCase(login.rejected, (state) => {
        state.user = null;
        state.csrfToken = getCsrfTokenFromMeta();
      })
      .addCase(logout.fulfilled, (state) => {
        state.user = null;
        state.csrfToken = null;
      })
      .addCase(getUser.fulfilled, (state, { payload }) => {
        state.user = payload.user;
        if (payload.csrfToken) {
          state.csrfToken = payload.csrfToken;
        }
        state.isLoading = false;
      })
      .addCase(getUser.rejected, (state) => {
        state.user = null;
        state.csrfToken = getCsrfTokenFromMeta();
        state.isLoading = false;
      });
  },
});

export const { clearAuth } = authSlice.actions;

export const authReducer = authSlice.reducer;
