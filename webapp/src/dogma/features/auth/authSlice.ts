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

axios.defaults.baseURL = process.env.NEXT_PUBLIC_HOST || '';

export const getUser = createAsyncThunk('/auth/user', async (_, { getState, rejectWithValue }) => {
  try {
    const { auth } = getState() as { auth: AuthState };
    if (!auth.sessionId) {
      return rejectWithValue('Login required');
    }
    const { data } = await axios.get(`/api/v0/users/me`, {
      headers: {
        Authorization: `Bearer ${auth.sessionId}`,
      },
    });
    return data as UserDto;
  } catch (error) {
    if (typeof window !== 'undefined') {
      localStorage.removeItem('sessionId');
    }
    if (error.response && error.response.data.message) {
      return rejectWithValue(error.response.data.message);
    } else {
      return rejectWithValue(error.message);
    }
  }
});

export interface LoginParams {
  username: string;
  password: string;
}

export const login = createAsyncThunk('/auth/login', async (params: LoginParams, { rejectWithValue }) => {
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
  } catch (error) {
    // TODO(ikhoon): Replace alert with Modal
    alert('Cannot sign in Central Dogma web console. Please check your account and password again.');
    if (typeof window !== 'undefined') {
      localStorage.removeItem('sessionId');
    }
    if (error.response && error.response.data.message) {
      return rejectWithValue(error.response.data.message);
    } else {
      return rejectWithValue(error.message);
    }
  }
});

interface UserSessionResponse {
  isAuthorized: boolean;
  user?: UserDto;
}

export const checkSecurityEnabled = createAsyncThunk<UserSessionResponse>(
  '/auth/securityEnabled',
  async (_, { rejectWithValue }) => {
    try {
      await axios.get(`/security_enabled`);
    } catch (error) {
      if (typeof window !== 'undefined') {
        localStorage.removeItem('sessionId');
      }
      if (error.response && error.response.data.message) {
        return rejectWithValue(error.response.data.message);
      } else {
        return rejectWithValue(error.message);
      }
    }
  },
);

const sessionId = typeof window !== 'undefined' && localStorage.getItem('sessionId');
export interface AuthState {
  isInAnonymousMode: boolean;
  sessionId: string;
  user: UserDto;
  ready: boolean;
}

const initialState: AuthState = {
  isInAnonymousMode: true,
  sessionId,
  user: null,
  ready: false,
};

export const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    logout: (state) => {
      if (typeof window !== 'undefined') {
        localStorage.removeItem('sessionId');
      }
      state.isInAnonymousMode = true;
      state.sessionId = '';
      state.user = null;
      state.ready = true;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(login.fulfilled, (state, { payload }) => {
        state.sessionId = payload.access_token;
      })
      .addCase(login.rejected, (state) => {
        state.sessionId = '';
      })
      .addCase(checkSecurityEnabled.fulfilled, (state) => {
        state.isInAnonymousMode = false;
      })
      .addCase(checkSecurityEnabled.rejected, (state) => {
        state.isInAnonymousMode = true;
        state.sessionId = '';
        state.ready = true;
      })
      .addCase(getUser.fulfilled, (state, { payload }) => {
        state.user = payload;
        state.ready = true;
      })
      .addCase(getUser.rejected, (state) => {
        state.user = null;
        state.ready = true;
        state.sessionId = '';
      });
  },
});

export const { logout } = authSlice.actions;
export const authReducer = authSlice.reducer;
