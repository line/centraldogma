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
import {
  Box,
  Button,
  chakra,
  Flex,
  FormControl,
  Image,
  Input,
  InputGroup,
  InputLeftElement,
  InputRightElement,
  Stack,
  VStack,
} from '@chakra-ui/react';
import { login, getUser } from 'dogma/features/auth/authSlice';
import { useAppDispatch } from 'dogma/hooks';
import { useForm } from 'react-hook-form';
import { useState } from 'react';
import { FaLock, FaUserAlt } from 'react-icons/fa';

type FormData = {
  username: string;
  password: string;
};

export const LoginForm = () => {
  const [showPassword, setShowPassword] = useState(false);

  const handleShowClick = () => setShowPassword(!showPassword);

  const { register, handleSubmit } = useForm<FormData>();
  const dispatch = useAppDispatch();

  const onSubmit = async (data: FormData) => {
    try {
      await dispatch(login({ username: data.username, password: data.password })).unwrap();
      dispatch(getUser());
    } catch (error) {
      console.error('Login failed:', error);
    }
  };
  const CFaUserAlt = chakra(FaUserAlt);
  const CFaLock = chakra(FaLock);

  return (
    <Flex
      flexDirection="column"
      width="100wh"
      height="100vh"
      backgroundColor="gray.300"
      justifyContent="center"
      alignItems="center"
    >
      <VStack mb="2" alignItems="center">
        <Image src="/central_dogma.png" height={120} marginBottom={4} alt="Central Dogma logo" />
        <Box minW={{ base: '90%', md: '468px' }}>
          <form onSubmit={handleSubmit(onSubmit)}>
            <Stack spacing={5} p="1rem" backgroundColor="whiteAlpha.900" boxShadow="md">
              <FormControl isRequired>
                <InputGroup>
                  <InputLeftElement pointerEvents="none">
                    <CFaUserAlt color="gray.300" />
                  </InputLeftElement>
                  <Input
                    id="username"
                    name="username"
                    type="text"
                    placeholder="ID"
                    {...register('username', { required: true })}
                  />
                </InputGroup>
              </FormControl>
              <FormControl>
                <InputGroup>
                  <InputLeftElement pointerEvents="none" color="gray.300">
                    <CFaLock color="gray.300" />
                  </InputLeftElement>
                  <Input
                    id="password"
                    name="password"
                    type={showPassword ? 'text' : 'password'}
                    placeholder="Password"
                    {...register('password', { required: true })}
                  />
                  <InputRightElement width="4.5rem">
                    <Button h="1.75rem" size="sm" onClick={handleShowClick}>
                      {showPassword ? 'Hide' : 'Show'}
                    </Button>
                  </InputRightElement>
                </InputGroup>
              </FormControl>
              <Button borderRadius={0} type="submit" variant="solid" colorScheme="teal" width="full">
                Login
              </Button>
            </Stack>
          </form>
        </Box>
        <Box height={120} />
      </VStack>
    </Flex>
  );
};
