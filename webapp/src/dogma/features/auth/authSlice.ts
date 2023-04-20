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
import axios from 'axios';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { createMessage } from 'dogma/features/message/messageSlice';

axios.defaults.baseURL = process.env.NEXT_PUBLIC_HOST || '';

export const getUser = createAsyncThunk('/auth/user', async (_, { getState, dispatch, rejectWithValue }) => {
  try {
    const { auth } = getState() as { auth: AuthState };
    if (!auth.sessionId) {
      return rejectWithValue('Login required');
    }
    // TODO(ikhoon): Replace axios with fetch
    const { data } = await axios.get(`/api/v0/users/me`, {
      headers: {
        Authorization: `Bearer ${auth.sessionId}`,
      },
    });
    return data as UserDto;
  } catch (err) {
    if (typeof window !== 'undefined') {
      localStorage.removeItem('sessionId');
    }
    const error: string = ErrorHandler.handle(err);
    dispatch(createMessage({ title: '', text: error, type: 'error' }));
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
      const { data } = await axios.post(`/api/v1/login`, params, {
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
        },
      });
      if (typeof window !== 'undefined') {
        localStorage.setItem('sessionId', data.access_token);
      }
      return data;
    } catch (err) {
      if (typeof window !== 'undefined') {
        localStorage.removeItem('sessionId');
      }
      const error: string = ErrorHandler.handle(err);
      dispatch(createMessage({ title: '', text: error, type: 'error' }));
      return rejectWithValue(error);
    }
  },
);

export const checkSecurityEnabled = createAsyncThunk(
  '/auth/securityEnabled',
  async (_, { dispatch, rejectWithValue }) => {
    try {
      await axios.get(`/security_enabled`);
    } catch (err) {
      if (typeof window !== 'undefined') {
        localStorage.removeItem('sessionId');
      }
      const error: string = ErrorHandler.handle(err);
      dispatch(createMessage({ title: '', text: 'Accessing Central Dogma in anonymous mode', type: 'info' }));
      return rejectWithValue(error);
    }
  },
);

export const logout = createAsyncThunk('/auth/logout', async (_, { getState, dispatch, rejectWithValue }) => {
  try {
    const { auth } = getState() as { auth: AuthState };
    await axios.post(`/api/v1/logout`, _, {
      headers: {
        Authorization: `Bearer ${auth.sessionId}`,
      },
    });
    if (typeof window !== 'undefined') {
      localStorage.removeItem('sessionId');
    }
  } catch (err) {
    const error: string = ErrorHandler.handle(err);
    dispatch(createMessage({ title: '', text: error, type: 'error' }));
    return rejectWithValue(error);
  }
});

const sessionId = typeof window !== 'undefined' && localStorage.getItem('sessionId');
export interface AuthState {
  isInAnonymousMode: boolean;
  sessionId: string;
  user: UserDto;
  isLoading: boolean;
}

const initialState: AuthState = {
  isInAnonymousMode: false,
  sessionId,
  user: null,
  isLoading: true,
};

export const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(checkSecurityEnabled.rejected, (state) => {
        state.isInAnonymousMode = true;
        state.sessionId = '';
        state.isLoading = false;
      })
      .addCase(login.fulfilled, (state, { payload }) => {
        state.sessionId = payload.access_token;
      })
      .addCase(login.rejected, (state) => {
        state.sessionId = '';
      })
      .addCase(logout.fulfilled, (state) => {
        state.sessionId = '';
        state.user = null;
      })
      .addCase(getUser.fulfilled, (state, { payload }) => {
        state.user = payload;
        state.isLoading = false;
      })
      .addCase(getUser.rejected, (state) => {
        state.user = null;
        state.sessionId = '';
        state.isLoading = false;
      });
  },
});

export const authReducer = authSlice.reducer;
