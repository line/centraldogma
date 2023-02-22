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
import { Spinner, Text, VStack, useColorMode } from '@chakra-ui/react';
import { FetchBaseQueryError } from '@reduxjs/toolkit/dist/query';
import Error from 'next/error';
import { ReactNode } from 'react';

interface LoadingProps {
  isLoading: boolean;
  error: any;
  children: () => ReactNode;
}
export const Deferred = (props: LoadingProps) => {
  const { colorMode } = useColorMode();
  if (props.isLoading) {
    return (
      <VStack mt="25%">
        <Spinner thickness="4px" speed="0.65s" emptyColor="gray.200" color="teal" size="xl" />
        <Text>Loading...</Text>
      </VStack>
    );
  }
  if (props.error) {
    return (
      <Error
        statusCode={(props.error as FetchBaseQueryError).status as number}
        withDarkMode={colorMode === 'dark'}
      />
    );
  }
  return <>{props.children()}</>;
};
