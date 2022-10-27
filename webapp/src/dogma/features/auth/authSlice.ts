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
import { history } from 'dogma/store';
import { UserDto } from 'dogma/features/auth/UserDto';
import { getSessionId, goToLoginPage, removeSessionId } from 'dogma/features/auth/Authorized';
import { HttpStatusCode } from 'dogma/features/api/HttpStatusCode';

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
    localStorage.setItem('sessionId', response.data.access_token);
    thunkAPI.dispatch(getUser());
    // TODO(ikhoon):
    //  - Link to the landing page
    //  - Link back to the original referer?
    history.push('/');
    return true;
  }

  // TODO(ikhoon): Replace alert with Modal
  // eslint-disable-next-line no-alert
  alert('Cannot sign in Central Dogma web console. Please check your account and password again.');
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
      goToLoginPage();
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
      goToLoginPage();
      return { isAuthorized: false };
    }
  }

  // Should not reach here in the normal case.
  goToLoginPage();
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
      });
  },
});

export const authReducer = authSlice.reducer;
