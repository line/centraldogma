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

import { Box, Heading, VStack } from '@chakra-ui/react';
import ProjectSearchBox from 'dogma/common/components/ProjectSearchBox';

const HomePage = () => {
  return (
    <div>
      <VStack spacing={4} align="center">
        <Heading as="h1" marginTop="20" marginBottom="20">
          Welcome to Central Dogma!
        </Heading>
        <Box width="80%" textAlign="center">
          <ProjectSearchBox id="home-search" size="lg" placeholder="Search project ..." autoFocus={true} />
        </Box>
      </VStack>
    </div>
  );
};

export default HomePage;
