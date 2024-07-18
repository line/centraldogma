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
import { getUser, checkSecurityEnabled } from 'dogma/features/auth/authSlice';
import { useRouter } from 'next/router';
import { isFulfilled } from '@reduxjs/toolkit';
import { useAppDispatch, useAppSelector } from 'dogma/hooks';

const WEB_AUTH_LOGIN = '/web/auth/login';

export const Authorized = (props: { children: ReactNode }) => {
  const dispatch = useAppDispatch();
  const { isLoading, isInAnonymousMode, user } = useAppSelector((state) => state.auth);

  useEffect(() => {
    const validateSession = async () => {
      const action = await dispatch(checkSecurityEnabled());
      if (isFulfilled(action)) {
        dispatch(getUser());
      }
    };
    validateSession();
  }, [dispatch]);

  const router = useRouter();
  if (isLoading) {
    return <p>Loading...</p>;
  }
  if (isInAnonymousMode || router.pathname === WEB_AUTH_LOGIN || user) {
    return <>{props.children}</>;
  }
  if (typeof window !== 'undefined') {
    router.push(
      process.env.NEXT_PUBLIC_HOST
        ? `${process.env.NEXT_PUBLIC_HOST}/link/auth/login/?return_to=${window.location.origin}`
        : `/link/auth/login/`,
    );
  }
  return <></>;
};
