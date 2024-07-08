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

import { useGetProjectsQuery } from 'dogma/features/api/apiSlice';
import { ProjectDto } from 'dogma/features/project/ProjectDto';
import { ProjectOptionType } from 'dogma/common/components/Navbar';
import { components, DropdownIndicatorProps, GroupBase, Select } from 'chakra-react-select';
import { useEffect, useState } from 'react';
import Router from 'next/router';
import { Box, Center, Flex, Heading, HStack, Kbd, Spacer, useColorMode, VStack } from '@chakra-ui/react';
import { SearchIcon } from '@chakra-ui/icons';

const initialState: ProjectOptionType = {
  value: '',
  label: '',
};

const DropdownIndicator = (
  props: JSX.IntrinsicAttributes & DropdownIndicatorProps<unknown, boolean, GroupBase<unknown>>,
) => {
  return (
    <components.DropdownIndicator {...props}>
      <SearchIcon />
    </components.DropdownIndicator>
  );
};

const HomePage = () => {
  const { colorMode } = useColorMode();
  const { data, isLoading } = useGetProjectsQuery({ admin: false });
  const projects = data || [];
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

  const [onClicked, setOnClicked] = useState(false);
  return (
    <div>
      <VStack spacing={4} align="center">
        <Heading as="h1" marginTop="20" marginBottom="20">
          Welcome to Central Dogma!
        </Heading>
        <Box width="80%" textAlign="center">
          <Select
            size="lg"
            id="project-select"
            name="project-search"
            options={projectOptions}
            value={selectedOption?.value}
            onChange={(option: ProjectOptionType) => option && handleChange(option)}
            onMenuOpen={() => setOnClicked(true)}
            onMenuClose={() => setOnClicked(false)}
            placeholder={!onClicked ? 'Search project ...' : ''}
            closeMenuOnSelect={true}
            openMenuOnFocus={true}
            isClearable={true}
            isSearchable={true}
            isLoading={isLoading}
            components={{ DropdownIndicator }}
            chakraStyles={{
              control: (baseStyles) => ({
                ...baseStyles,
                backgroundColor: colorMode === 'light' ? 'white' : 'whiteAlpha.50',
              }),
            }}
          />
        </Box>
      </VStack>
    </div>
  );
};

export default HomePage;
