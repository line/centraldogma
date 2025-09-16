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

import { ReactNode, useEffect, useState } from 'react';
import { getUser, checkSecurityEnabled } from 'dogma/features/auth/authSlice';
import { useRouter } from 'next/router';
import { isFulfilled } from '@reduxjs/toolkit';
import { useAppDispatch, useAppSelector } from 'dogma/hooks';
import { Loading } from '../../common/components/Loading';
import { createLoginUrl } from 'dogma/util/auth';

const WEB_AUTH_LOGIN = '/web/auth/login';

export const Authorized = (props: { children: ReactNode }) => {
  const dispatch = useAppDispatch();
  const { isLoading, isInAnonymousMode, user } = useAppSelector((state) => state.auth);
  const router = useRouter();

  const [isInitialized, setIsInitialized] = useState(false);

  useEffect(() => {
    if (!isInitialized) {
      const validateSession = async () => {
        const action = await dispatch(checkSecurityEnabled());
        if (isFulfilled(action)) {
          dispatch(getUser());
        }
      };
      validateSession();
      setIsInitialized(true);
    }
  }, [dispatch, isInitialized]);

  useEffect(() => {
    // Need to use useEffect for the router.push which is a side effect.
    if (isLoading) {
      return;
    }

    const isAuthorized = isInAnonymousMode || user;
    const isOnLoginPage = router.pathname === WEB_AUTH_LOGIN;

    if (!isAuthorized && !isOnLoginPage) {
      router.push(createLoginUrl());
    }
  }, [isLoading, isInAnonymousMode, user, router, isInitialized]);

  const isAuthorized = isInAnonymousMode || user;
  const isOnLoginPage = router.pathname === WEB_AUTH_LOGIN;

  if (isLoading || (!isAuthorized && !isOnLoginPage)) {
    return <Loading />;
  }

  return <>{props.children}</>;
};
