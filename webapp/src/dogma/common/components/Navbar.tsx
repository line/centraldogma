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
import React, { ReactNode } from 'react';
import {
  Avatar,
  Box,
  Button,
  Flex,
  HStack,
  IconButton,
  Input,
  InputGroup,
  InputLeftElement,
  InputRightElement,
  Kbd,
  Link,
  Menu,
  MenuButton,
  MenuDivider,
  MenuItem,
  MenuList,
  Stack,
  useColorMode,
  useColorModeValue,
  useDisclosure,
} from '@chakra-ui/react';
import { AddIcon, CloseIcon, HamburgerIcon, MoonIcon, SearchIcon, SunIcon } from '@chakra-ui/icons';
import { default as RouteLink } from 'next/link';
import { useAppSelector, useAppDispatch } from 'dogma/store';
import { logout } from 'dogma/features/auth/authSlice';

interface TopMenu {
  name: string;
  path: string;
}

// TODO(ikhoon): Add more top menus
const Links: TopMenu[] = [{ name: 'Projects', path: '/app/projects' }];

const NavLink = ({ link, children }: { link: string; children: ReactNode }) => (
  <Link
    as={RouteLink}
    px={2}
    py={1}
    rounded="md"
    _hover={{
      textDecoration: 'none',
      bg: useColorModeValue('gray.200', 'gray.700'),
    }}
    href={link}
  >
    {children}
  </Link>
);

export const Navbar = () => {
  const { isOpen, onOpen, onClose } = useDisclosure();
  const { colorMode, toggleColorMode } = useColorMode();
  const user = useAppSelector((state) => state.auth.user);
  const dispatch = useAppDispatch();
  return (
    <Box bg={useColorModeValue('gray.100', 'gray.900')} px={4}>
      <Flex h={16} alignItems="center" justifyContent="space-between" fontWeight="semibold">
        <IconButton
          size="md"
          icon={isOpen ? <CloseIcon /> : <HamburgerIcon />}
          aria-label="Open Menu"
          display={{ md: 'none' }}
          onClick={isOpen ? onClose : onOpen}
        />
        <HStack spacing={8} alignItems="center">
          <Box>Central Dogma</Box>
          <HStack as="nav" spacing={4} display={{ base: 'none', md: 'flex' }}>
            {Links.map(({ path, name }) => (
              <NavLink link={path} key={name}>
                {name}
              </NavLink>
            ))}
          </HStack>
        </HStack>
        <HStack>
          <InputGroup size="md">
            <InputLeftElement>
              <SearchIcon />
            </InputLeftElement>
            <Input width="sm" placeholder="Jump to..." />
            <InputRightElement>
              {/* TODO(ikhoon): focus on the search bar with `/` key press */}
              <Kbd>/</Kbd>
            </InputRightElement>
          </InputGroup>
        </HStack>
        <Flex alignItems="center">
          <Button variant="solid" colorScheme="teal" size="sm" mr={4} leftIcon={<AddIcon />}>
            New Project
          </Button>
          <Button onClick={toggleColorMode}>{colorMode === 'light' ? <MoonIcon /> : <SunIcon />}</Button>
          {user ? (
            <Menu>
              <MenuButton as={Button} rounded="full" variant="link" cursor="pointer" minW={0}>
                {/* TODO(ikhoon): Use a profile image if an auth provider provides? */}
                <Avatar name={user.login} size="sm" />
              </MenuButton>
              <MenuList>
                <MenuItem>Application tokens</MenuItem>
                <MenuItem>Add ...</MenuItem>
                <MenuDivider />
                <MenuItem onClick={() => dispatch(logout())}>Log out</MenuItem>
              </MenuList>
            </Menu>
          ) : (
            <div />
          )}
        </Flex>
      </Flex>

      {isOpen ? (
        <Box pb={4} display={{ md: 'none' }}>
          <Stack as="nav" spacing={4}>
            {Links.map(({ path, name }) => (
              <NavLink link={path} key={name}>
                {name}
              </NavLink>
            ))}
          </Stack>
        </Box>
      ) : null}
    </Box>
  );
};
