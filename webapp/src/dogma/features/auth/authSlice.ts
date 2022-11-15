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
import axios from 'axios';
import qs from 'qs';
import { UserDto } from 'dogma/features/auth/UserDto';
import { HttpStatusCode } from 'dogma/features/api/HttpStatusCode';
import { setSessionId, removeSessionId, getSessionId } from 'dogma/features/auth/util';

const getUser = createAsyncThunk('/auth/user', async () => {
  // TODO(ikhoon): Just use fetch API?
  const response = await axios.get('/api/v0/users/me');
  return response.data as UserDto;
});

export interface LoginParams {
  username: string;
  password: string;
}

export const login = createAsyncThunk('/auth/login', async (params: LoginParams, thunkAPI) => {
  const response = await axios.post('/api/v1/login', qs.stringify(params), {
    validateStatus: (status) => status < 500,
  });
  if (response.status === HttpStatusCode.Ok) {
    setSessionId(response.data.access_token);
    await thunkAPI.dispatch(getUser());
    return true;
  }

  // TODO(ikhoon): Replace alert with Modal
  alert('Cannot sign in Central Dogma web console. Please check your account and password again.');
  return false;
});

export const logout = createAsyncThunk('/auth/logout', async () => {
  const response = await axios.post('/api/v1/logout', null, {
    validateStatus: (status) => status < 500,
  });
  if (response.status === HttpStatusCode.Ok) {
    removeSessionId();
    return true;
  }
  alert('Problem logging out. Please try again.');
  return false;
});

interface UserSessionResponse {
  isAuthorized: boolean;
  user?: UserDto;
}

export const validateSession = createAsyncThunk<UserSessionResponse>('/auth/validate_session', async () => {
  const response = await axios.get('/security_enabled', {
    validateStatus: (status) => status < 500,
  });
  if (response.status === HttpStatusCode.NotFound) {
    // Anonymous mode
    removeSessionId();
    return { isAuthorized: true };
  }

  if (response.status === HttpStatusCode.Ok) {
    if (getSessionId() === null) {
      return { isAuthorized: false };
    }

    const userResponse = await axios.get('/api/v0/users/me', {
      validateStatus: (status) => status < 500,
    });

    if (userResponse.status === HttpStatusCode.Ok) {
      // The current session ID is valid. Login is not required.
      return { isAuthorized: true, user: userResponse.data as UserDto };
    }

    if (userResponse.status === HttpStatusCode.Unauthorized) {
      removeSessionId();
      return { isAuthorized: false };
    }
  }

  // Should not reach here in the normal case.
  return { isAuthorized: false };
});

export interface AuthState {
  isAuthenticated: boolean;
  user: UserDto;
}

const initialState: AuthState = {
  isAuthenticated: false,
  user: null,
};

export const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(login.fulfilled, (status, action) => {
        if (action.payload != null) {
          status.isAuthenticated = true;
        }
      })
      .addCase(validateSession.fulfilled, (status, action) => {
        if (action.payload.isAuthorized) {
          status.isAuthenticated = true;
        }
        if (action.payload.user != null) {
          status.user = action.payload.user;
        }
      })
      .addCase(getUser.fulfilled, (status, action) => {
        status.user = action.payload;
      })
      .addCase(logout.fulfilled, (status, action) => {
        if (action.payload) {
          status.isAuthenticated = false;
          status.user = null;
        }
      });
  },
});

export const authReducer = authSlice.reducer;
