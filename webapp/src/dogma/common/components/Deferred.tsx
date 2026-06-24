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
import { useColorMode } from '@chakra-ui/react';
import Error from 'next/error';
import { ReactNode } from 'react';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { Loading } from './Loading';

interface LoadingProps {
  isLoading: boolean;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  error: any;
  children: () => ReactNode;
}
export const Deferred = (props: LoadingProps) => {
  const { colorMode } = useColorMode();
  if (props.isLoading) {
    return <Loading />;
  }
  if (props.error) {
    const error = props.error;
    const message = ErrorMessageParser.parse(error);
    return <Error statusCode={error.status as number} withDarkMode={colorMode === 'dark'} title={message} />;
  }
  return <>{props.children()}</>;
};
