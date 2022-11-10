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

import React from 'react';
import { useAppDispatch, useAppSelector } from 'dogma/store';
import { validateSession } from 'dogma/features/auth/authSlice';

import axios from 'axios';
import { Layout } from 'dogma/common/components/Layout';
import Router from 'next/router';

export function setSessionId(id: string) {
  if (typeof window !== 'undefined') {
     localStorage.setItem('sessionId', id);
  }
}

export function getSessionId(): string | null {
  if (typeof window !== 'undefined') {
    return localStorage.getItem('sessionId');
  }
  return null;
}

export function removeSessionId() {
  if (typeof window !== 'undefined') {
    localStorage.removeItem('sessionId');
  }
}

export function goToLoginPage() {
  Router.push('/');
}

export function goToPage(page: string) {
  Router.push(page);
}

axios.interceptors.request.use((config) => {
  if (config.url !== '/api/v1/login') {
    const sessionId = getSessionId();
    if (sessionId != null) {
      config.headers.Authorization = `Bearer ${sessionId}`;
    }
  }
  return config;
});

export const Authorized = () => {
  const dispatch = useAppDispatch();

  const auth = useAppSelector((state) => state.auth);

  if (auth.isAuthenticated) {
    return (
      <Layout>
      </Layout>
    );
  }

  dispatch(validateSession());
  return null;
};
