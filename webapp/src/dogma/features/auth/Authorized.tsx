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

import { ReactNode, useEffect } from 'react';
import { useAppDispatch, useAppSelector } from 'dogma/store';
import { validateSession } from 'dogma/features/auth/authSlice';
import axios from 'axios';
import { useRouter } from 'next/router';
import { getSessionId, WEB_AUTH_LOGIN } from 'dogma/features/auth/util';

axios.interceptors.request.use((config) => {
  if (config.url !== '/api/v1/login') {
    const sessionId = getSessionId();
    if (sessionId != null) {
      config.headers.Authorization = `Bearer ${sessionId}`;
    }
  }
  return config;
});

export const Authorized = (props: { children: ReactNode }) => {
  const dispatch = useAppDispatch();
  useEffect(() => {
    dispatch(validateSession());
  }, [dispatch]);
  const auth = useAppSelector((state) => state.auth);
  const router = useRouter();
  if (!auth.ready) {
    return <p>Loading...</p>;
  }
  if (auth.isAuthenticated) {
    return <>{props.children}</>;
  }
  if (router.pathname === WEB_AUTH_LOGIN) {
    return <>{props.children}</>;
  }
  if (typeof window !== 'undefined' && !auth.isAuthenticated) {
    if (process.env.NEXT_PUBLIC_HOST) {
      router.push(`${process.env.NEXT_PUBLIC_HOST}/link/auth/login/?return_to=${window.location.origin}`);
    } else {
      router.push(`/link/auth/login/`);
    }
  }
  return <></>;
};
