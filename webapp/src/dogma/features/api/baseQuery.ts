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

import { BaseQueryFn, FetchArgs, fetchBaseQuery } from '@reduxjs/toolkit/query/react';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { AuthState, clearAuth } from 'dogma/features/auth/authSlice';

const baseQuery = fetchBaseQuery({
  baseUrl: `${process.env.NEXT_PUBLIC_HOST || ''}/`,
  credentials: 'include',
  // YAML and plain-text error responses must not be JSON-parsed. 'content-type' switches between
  // JSON.parse (application/json) and raw text (everything else) based on the response header.
  responseHandler: 'content-type',
  prepareHeaders: (headers, { getState, type }) => {
    const { auth } = getState() as { auth: AuthState };

    if (auth.isInAnonymousMode) {
      // In anonymous mode, the server requires 'Authorization: Bearer anonymous'
      // to pass through AnonymousTokenAuthorizer.
      headers.set('Authorization', 'Bearer anonymous');
      return headers;
    }

    if (type === 'mutation') {
      const csrfToken = auth.csrfToken;

      if (csrfToken) {
        headers.set('X-CSRF-Token', csrfToken);
      }
      if (!headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json; charset=UTF-8');
      }
    }
    return headers;
  },
});

/**
 * Creates a base query that, on a 401 for a non-anonymous session, clears the auth state and redirects to the
 * login page via {@code redirectToLogin}. The redirect itself differs between apps: the main web app uses the
 * Next.js router, while the xDS app (served under the '/xds' basePath) navigates out of the SPA.
 */
export function baseQueryWithReauth(
  redirectToLogin: () => void,
): BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError> {
  return async (args, api, extraOptions) => {
    const result = await baseQuery(args, api, extraOptions);

    if (result.error && result.error.status === 401) {
      const { auth } = api.getState() as { auth: AuthState };
      if (!auth.isInAnonymousMode) {
        api.dispatch(clearAuth());
        redirectToLogin();
      }
    }
    return result;
  };
}
