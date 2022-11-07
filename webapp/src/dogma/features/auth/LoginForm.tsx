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

import { Box, Button, Flex, FormControl, Input, VStack } from '@chakra-ui/react';
import { Field, Form, Formik } from 'formik';
import { login } from 'dogma/features/auth/authSlice';
import { useAppDispatch } from 'dogma/store';

export const LoginForm = () => {
  const dispatch = useAppDispatch();

  // Redux => state
  // TODO(ikhoon): Beautify
  return (
    <Flex bg="gray.100" align="center" justify="center" h="100vh">
      <Box bg="white" p={6} rounded="md" w={64}>
        <Formik
          initialValues={{
            username: '',
            password: '',
          }}
          onSubmit={(values) => {
            dispatch(login({ username: values.username, password: values.password }));
          }}
        >
          <Form>
            <VStack spacing={4} align="flex-start">
              <FormControl isRequired>
                <Field as={Input} id="username" name="username" type="text" variant="filled" placeholder="ID" />
              </FormControl>
              <FormControl isRequired>
                <Field
                  as={Input}
                  id="password"
                  name="password"
                  type="password"
                  variant="filled"
                  placeholder="Password"
                />
              </FormControl>
              <Button type="submit">Submit</Button>
            </VStack>
          </Form>
        </Formik>
      </Box>
    </Flex>
  );
};
