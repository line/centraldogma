/*
 * Copyright 2024 LINE Corporation
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

import Router, { useRouter } from 'next/router';
import { useGetFilesQuery } from 'dogma/features/api/apiSlice';
import { Deferred } from 'dogma/common/components/Deferred';
import {
  Box,
  Button,
  Heading,
  HStack,
  Input,
  InputGroup,
  InputLeftAddon,
  Spacer,
  useColorMode,
} from '@chakra-ui/react';
import React, { useState } from 'react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import FourOhFour from 'pages/404';
import { FaArrowLeftLong, FaCodeCompare } from 'react-icons/fa6';
import { GoDiff } from 'react-icons/go';
import DiffView, { DiffMode } from 'dogma/common/components/DiffView';
import DiffModeButton from 'dogma/common/components/DiffModeButton';
import { useForm } from 'react-hook-form';

type FormData = {
  baseRevision: number;
  headRevision: number;
};

const ChangesViewPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const repoName = router.query.repoName as string;
  const headRevision = parseInt(router.query.revision as string);
  const baseRevision = parseInt(router.query.baseRevision as string);
  const filePath = '/**';

  const {
    data: newData,
    isLoading: isNewLoading,
    error: newError,
  } = useGetFilesQuery({
    projectName,
    repoName,
    revision: headRevision,
    filePath,
    withContent: true,
  });
  const {
    data: oldData,
    isLoading: isOldLoading,
    error: oldError,
  } = useGetFilesQuery({
    projectName,
    repoName,
    revision: baseRevision,
    filePath,
    withContent: true,
  });

  const { colorMode } = useColorMode();
  const [diffMode, setDiffMode] = useState<DiffMode>('Split');

  const {
    register,
    handleSubmit,
    formState: { isDirty },
  } = useForm<FormData>({
    defaultValues: {
      baseRevision: baseRevision,
      headRevision: headRevision,
    },
  });
  const onSubmit = (data: FormData) => {
    Router.push(
      `/app/projects/${projectName}/repos/${repoName}/compare/${data.headRevision}/base/${data.baseRevision}`,
    );
  };

  if (headRevision <= 1) {
    return <FourOhFour title={`There isn’t anything to compare.`} />;
  }

  return (
    <Deferred isLoading={isNewLoading || isOldLoading} error={newError || oldError}>
      {() => {
        return (
          <Box>
            <Breadcrumbs path={router.asPath} omitIndexList={[0, 3, 6, 7, 8]} unlinkedList={[5]} />
            <Heading size="lg" marginBottom={5}>
              <HStack color="teal">
                <Box>
                  <GoDiff />
                </Box>
                <Box>Changes</Box>
              </HStack>
            </Heading>
            <HStack marginBottom={5}>
              <Box>
                <form onSubmit={handleSubmit(onSubmit)}>
                  <HStack>
                    <Box>
                      <InputGroup size={'sm'}>
                        <InputLeftAddon color={'gray.500'} borderLeftRadius={'md'}>
                          Base
                        </InputLeftAddon>
                        <Input
                          size="sm"
                          type="number"
                          borderRightRadius="md"
                          {...register('baseRevision', { required: true, min: 1, max: headRevision - 1 })}
                        />
                      </InputGroup>
                    </Box>
                    <Box>
                      <FaArrowLeftLong />
                    </Box>
                    <Box>
                      <InputGroup size={'sm'}>
                        <InputLeftAddon color={'gray.500'} borderLeftRadius={'md'}>
                          From
                        </InputLeftAddon>
                        <Input
                          type="number"
                          borderRightRadius="md"
                          {...register('headRevision', { required: true, min: 1, max: headRevision })}
                        />
                      </InputGroup>
                    </Box>
                    <Box>
                      <Button
                        type="submit"
                        size={'sm'}
                        leftIcon={<FaCodeCompare />}
                        colorScheme="green"
                        isDisabled={!isDirty}
                      >
                        Compare
                      </Button>
                    </Box>
                  </HStack>
                </form>
              </Box>
              <Spacer />
              <DiffModeButton onChange={(value) => setDiffMode(value as DiffMode)} />
            </HStack>
            <DiffView
              projectName={projectName}
              repoName={repoName}
              revision={headRevision}
              oldData={oldData || []}
              newData={newData || []}
              diffMode={diffMode}
              colorMode={colorMode}
            />
          </Box>
        );
      }}
    </Deferred>
  );
};
export default ChangesViewPage;
