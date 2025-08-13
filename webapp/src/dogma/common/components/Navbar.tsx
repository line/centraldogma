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
import { ReactNode } from 'react';
import {
  Box,
  Button,
  Flex,
  HStack,
  IconButton,
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
import { CloseIcon, HamburgerIcon, MoonIcon, SunIcon } from '@chakra-ui/icons';
import { default as RouteLink } from 'next/link';
import { logout } from 'dogma/features/auth/authSlice';
import Router from 'next/router';
import { useGetTitleQuery } from 'dogma/features/api/apiSlice';
import { NewProject } from 'dogma/features/project/NewProject';
import { usePathname } from 'next/navigation';
import { useAppDispatch, useAppSelector } from 'dogma/hooks';
import { LabelledIcon } from 'dogma/common/components/LabelledIcon';
import { FaUser } from 'react-icons/fa';
import ProjectSearchBox from 'dogma/common/components/ProjectSearchBox';

interface TopMenu {
  name: string;
  path: string;
}

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
  const { user } = useAppSelector((state) => state.auth);
  const dispatch = useAppDispatch();
  const pathname = usePathname();

  const { data: titleDto } = useGetTitleQuery();
  const title = titleDto?.title.replace('{{hostname}}', titleDto.hostname) || 'Central Dogma';
  const topMenus: TopMenu[] = [
    { name: title, path: '/' },
    { name: 'Projects', path: '/app/projects' },
    { name: 'Settings', path: '/app/settings' },
  ];

  return (
    <Box bg={useColorModeValue('gray.100', 'gray.900')} px={4}>
      <title>{title}</title>
      <Flex h={16} alignItems="center" justifyContent="space-between" fontWeight="semibold">
        <IconButton
          size="md"
          icon={isOpen ? <CloseIcon /> : <HamburgerIcon />}
          aria-label="Open Menu"
          display={{ md: 'none' }}
          onClick={isOpen ? onClose : onOpen}
        />
        <HStack spacing={8} alignItems="center">
          <HStack as="nav" spacing={4} display={{ base: 'none', md: 'flex' }}>
            {topMenus.map(({ path, name }) => (
              <NavLink link={path} key={name}>
                {name}
              </NavLink>
            ))}
          </HStack>
        </HStack>
        {pathname === '/' ? (
          <div />
        ) : (
          <Box w="40%">
            <ProjectSearchBox id="nav-search" placeholder="Jump to project ..." />
          </Box>
        )}
        <Flex alignItems="center" gap={2}>
          <NewProject />
          <IconButton
            aria-label="Toggle color mode"
            icon={colorMode === 'light' ? <MoonIcon /> : <SunIcon />}
            onClick={toggleColorMode}
            mr={2}
          />
          {user ? (
            <Menu>
              <MenuButton as={Button} rounded="full" variant="link" cursor="pointer" marginRight={2} minW={0}>
                {/* TODO(ikhoon): Use a profile image if an auth provider provides? */}
                <LabelledIcon icon={FaUser} text={user.login} />
              </MenuButton>
              <MenuList>
                <MenuItem as={RouteLink} href="/app/settings/tokens">
                  Application tokens
                </MenuItem>
                {user.systemAdmin && (
                  <MenuItem as={RouteLink} href="/app/settings/server-status">
                    Server status
                  </MenuItem>
                )}
                <MenuDivider />
                <MenuItem
                  onClick={async () => {
                    await dispatch(logout());
                    if (typeof window !== 'undefined') {
                      Router.push(
                        process.env.NEXT_PUBLIC_HOST
                          ? `${process.env.NEXT_PUBLIC_HOST}/link/auth/login?return_to=${window.location.origin}`
                          : `/link/auth/login`,
                      );
                    }
                  }}
                >
                  Log out
                </MenuItem>
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
            {topMenus.map(({ path, name }) => (
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
