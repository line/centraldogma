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
import { Box, Button, Flex, FormControl, Input, VStack, useColorMode } from '@chakra-ui/react';
import { login } from 'dogma/features/auth/authSlice';
import { useAppDispatch } from 'dogma/store';
import { useForm } from 'react-hook-form';

type FormData = {
  username: string;
  password: string;
};

export const LoginForm = () => {
  const { register, handleSubmit } = useForm<FormData>();
  const dispatch = useAppDispatch();
  const onSubmit = (data: FormData) => dispatch(login({ username: data.username, password: data.password }));
  const { colorMode } = useColorMode();
  return (
    <Flex bg={colorMode === 'light' && 'gray.100'} align="center" justify="center" h="100vh">
      <Box bg={colorMode === 'light' && 'white'} p={6} rounded="md" w={64}>
        <form onSubmit={handleSubmit(onSubmit)}>
          <VStack spacing={4} align="flex-start">
            <FormControl isRequired>
              <Input
                id="username"
                name="username"
                type="text"
                variant="filled"
                placeholder="ID"
                {...register('username', { required: true })}
              />
            </FormControl>
            <FormControl isRequired>
              <Input
                id="password"
                name="password"
                type="password"
                variant="filled"
                placeholder="Password"
                {...register('password', { required: true })}
              />
            </FormControl>
            <Button type="submit">Submit</Button>
          </VStack>
        </form>
      </Box>
    </Flex>
  );
};
