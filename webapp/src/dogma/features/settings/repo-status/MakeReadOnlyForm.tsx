/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
  Flex,
  FormControl,
  FormLabel,
  Heading,
  useColorMode,
  useDisclosure,
} from '@chakra-ui/react';
import { OptionBase, Select } from 'chakra-react-select';
import { useState } from 'react';
import {
  useGetProjectsQuery,
  useGetReposQuery,
  useUpdateRepositoryStatusMutation,
} from 'dogma/features/api/apiSlice';
import { ProjectDto } from 'dogma/features/project/ProjectDto';
import { RepoDto } from 'dogma/features/repo/RepoDto';
import { RepoStatusConfirmModal } from 'dogma/features/settings/repo-status/RepoStatusConfirmModal';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import { useAppDispatch } from 'dogma/hooks';

interface Option extends OptionBase {
  value: string;
  label: string;
}

const MakeReadOnlyForm = () => {
  const { colorMode } = useColorMode();
  const { isOpen, onOpen, onClose } = useDisclosure();
  const dispatch = useAppDispatch();

  const [project, setProject] = useState<Option | null>(null);
  const [repo, setRepo] = useState<Option | null>(null);

  const { data: projects = [], isLoading: projectsLoading } = useGetProjectsQuery({ systemAdmin: false });
  const { data: repos = [], isFetching: reposFetching } = useGetReposQuery(project?.value ?? '', {
    skip: !project,
  });
  const [updateRepositoryStatus, { isLoading: submitting }] = useUpdateRepositoryStatusMutation();

  const projectOptions: Option[] = projects.map((p: ProjectDto) => ({ value: p.name, label: p.name }));
  const repoOptions: Option[] = repos.map((r: RepoDto) => ({ value: r.name, label: r.name }));

  const handleConfirm = async () => {
    if (!project || !repo) {
      return;
    }
    try {
      await updateRepositoryStatus({
        projectName: project.value,
        repoName: repo.value,
        status: 'READ_ONLY',
      }).unwrap();
      dispatch(
        newNotification(
          'Repository is now read-only',
          `${project.value}/${repo.value} is now read-only`,
          'success',
        ),
      );
      onClose();
      setRepo(null);
    } catch (error) {
      dispatch(
        newNotification(
          `Failed to make ${project.value}/${repo.value} read-only`,
          ErrorMessageParser.parse(error),
          'error',
        ),
      );
    }
  };

  const controlStyles = {
    control: (baseStyles: Record<string, unknown>) => ({
      ...baseStyles,
      backgroundColor: colorMode === 'light' ? 'white' : 'whiteAlpha.50',
    }),
  };

  return (
    <Box borderWidth="1px" borderRadius="md" p="4" mb="8">
      <Heading size="md" mb="4">
        Make a repository read-only
      </Heading>
      <Flex gap={4} align="flex-end" wrap="wrap">
        <FormControl maxW="xs">
          <FormLabel>Project</FormLabel>
          <Select<Option>
            id="readonly-project-select"
            name="readonly-project"
            options={projectOptions}
            value={project}
            onChange={(option: Option | null) => {
              setProject(option);
              setRepo(null);
            }}
            isLoading={projectsLoading}
            isClearable
            isSearchable
            placeholder="Select project..."
            chakraStyles={controlStyles}
          />
        </FormControl>
        <FormControl maxW="xs">
          <FormLabel>Repository</FormLabel>
          <Select<Option>
            id="readonly-repo-select"
            name="readonly-repo"
            options={repoOptions}
            value={repo}
            onChange={(option: Option | null) => setRepo(option)}
            isLoading={reposFetching}
            isDisabled={!project}
            isClearable
            isSearchable
            placeholder="Select repository..."
            chakraStyles={controlStyles}
          />
        </FormControl>
        <Button colorScheme="red" onClick={onOpen} isDisabled={!project || !repo}>
          Make read-only
        </Button>
      </Flex>
      {project && repo && (
        <RepoStatusConfirmModal
          isOpen={isOpen}
          onClose={onClose}
          projectName={project.value}
          repoName={repo.value}
          targetStatus="READ_ONLY"
          onConfirm={handleConfirm}
          isLoading={submitting}
        />
      )}
    </Box>
  );
};

export default MakeReadOnlyForm;
