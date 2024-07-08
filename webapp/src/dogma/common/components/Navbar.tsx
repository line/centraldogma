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
import { ReactNode, useEffect, useState, useRef } from 'react';
import {
  Avatar,
  Box,
  Button,
  Flex,
  HStack,
  IconButton,
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
import { CloseIcon, HamburgerIcon, MoonIcon, SunIcon } from '@chakra-ui/icons';
import { default as RouteLink } from 'next/link';
import { useAppSelector, useAppDispatch } from 'dogma/store';
import { logout } from 'dogma/features/auth/authSlice';
import Router from 'next/router';
import { useGetProjectsQuery } from 'dogma/features/api/apiSlice';
import { ProjectDto } from 'dogma/features/project/ProjectDto';
import { components, DropdownIndicatorProps, GroupBase, OptionBase, Select } from 'chakra-react-select';
import { NewProject } from 'dogma/features/project/NewProject';
import { usePathname } from 'next/navigation';

interface TopMenu {
  name: string;
  path: string;
}

const topMenus: TopMenu[] = [
  { name: 'Central Dogma', path: '/' },
  { name: 'Projects', path: '/app/projects' },
];

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

export interface ProjectOptionType extends OptionBase {
  value: string;
  label: string;
}

const initialState: ProjectOptionType = {
  value: '',
  label: '',
};

const DropdownIndicator = (
  props: JSX.IntrinsicAttributes & DropdownIndicatorProps<unknown, boolean, GroupBase<unknown>>,
) => {
  return (
    <components.DropdownIndicator {...props}>
      <Kbd>/</Kbd>
    </components.DropdownIndicator>
  );
};

export const Navbar = () => {
  const { isOpen, onOpen, onClose } = useDisclosure();
  const { colorMode, toggleColorMode } = useColorMode();
  const { user } = useAppSelector((state) => state.auth);
  const dispatch = useAppDispatch();
  const result = useGetProjectsQuery({ admin: false });
  const projects = result.data || [];
  const projectOptions: ProjectOptionType[] = projects.map((project: ProjectDto) => ({
    value: project.name,
    label: project.name,
  }));
  const [selectedOption, setSelectedOption] = useState(initialState);
  const handleChange = (option: ProjectOptionType) => {
    setSelectedOption(option);
  };
  useEffect(() => {
    if (selectedOption?.value) {
      Router.push(`/app/projects/${selectedOption.value}`);
    }
  }, [selectedOption?.value]);

  const selectRef = useRef(null);
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const target = (e.target as HTMLElement).tagName.toLowerCase();
      if (target == 'textarea' || target == 'input') {
        return;
      }
      if (e.key === '/') {
        e.preventDefault();
        selectRef.current.clearValue();
        selectRef.current.focus();
      } else if (e.key === 'Escape') {
        selectRef.current.blur();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, []);

  const pathname = usePathname();

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
            <Select
              id="color-select"
              name="project-search"
              options={projectOptions}
              value={selectedOption?.value}
              onChange={(option: ProjectOptionType) => option && handleChange(option)}
              placeholder="Jump to project ..."
              closeMenuOnSelect={true}
              openMenuOnFocus={true}
              isClearable={true}
              isSearchable={true}
              ref={selectRef}
              components={{ DropdownIndicator }}
              chakraStyles={{
                control: (baseStyles) => ({
                  ...baseStyles,
                  backgroundColor: colorMode === 'light' ? 'white' : 'whiteAlpha.50',
                }),
              }}
            />
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
              <MenuButton as={Button} rounded="full" variant="link" cursor="pointer" minW={0}>
                {/* TODO(ikhoon): Use a profile image if an auth provider provides? */}
                <Avatar name={user.login} size="sm" />
              </MenuButton>
              <MenuList>
                <MenuItem as={RouteLink} href="/app/settings/tokens">
                  Application tokens
                </MenuItem>
                <MenuDivider />
                <MenuItem
                  onClick={async () => {
                    await dispatch(logout());
                    if (typeof window !== 'undefined') {
                      Router.push(
                        process.env.NEXT_PUBLIC_HOST
                          ? `${process.env.NEXT_PUBLIC_HOST}/link/auth/login/?return_to=${window.location.origin}`
                          : `/link/auth/login/`,
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
