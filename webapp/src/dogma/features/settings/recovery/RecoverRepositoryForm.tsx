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
  Alert,
  AlertIcon,
  Box,
  Button,
  Flex,
  FormControl,
  FormHelperText,
  FormLabel,
  Heading,
  NumberDecrementStepper,
  NumberIncrementStepper,
  NumberInput,
  NumberInputField,
  NumberInputStepper,
  Text,
  useColorMode,
  useDisclosure,
} from '@chakra-ui/react';
import { OptionBase, Select } from 'chakra-react-select';
import { useState } from 'react';
import {
  useGetProjectsQuery,
  useGetReplicasQuery,
  useGetReposQuery,
  useRecoverRepositoryMutation,
} from 'dogma/features/api/apiSlice';
import { ProjectDto } from 'dogma/features/project/ProjectDto';
import { RepoDto } from 'dogma/features/repo/RepoDto';
import { RecoverRepositoryResponse, ReplicaInfo } from 'dogma/features/settings/recovery/RecoveryDto';
import { RecoveryConfirmModal } from 'dogma/features/settings/recovery/RecoveryConfirmModal';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import { useAppDispatch } from 'dogma/hooks';

interface Option extends OptionBase {
  value: string;
  label: string;
}

interface SourceOption extends OptionBase {
  value: number;
  label: string;
  host: string;
}

const RecoverRepositoryForm = () => {
  const { colorMode } = useColorMode();
  const { isOpen, onOpen, onClose } = useDisclosure();
  const dispatch = useAppDispatch();

  const [project, setProject] = useState<Option | null>(null);
  const [repo, setRepo] = useState<Option | null>(null);
  const [source, setSource] = useState<SourceOption | null>(null);
  const [fromRevision, setFromRevision] = useState(2);
  // Inline feedback, so the outcome stays visible even after the transient toast is gone.
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<{ target: string; response: RecoverRepositoryResponse } | null>(
    null,
  );

  const { data: projects = [], isLoading: projectsLoading } = useGetProjectsQuery({ systemAdmin: false });
  const { data: repos = [], isFetching: reposFetching } = useGetReposQuery(project?.value ?? '', {
    skip: !project,
  });
  const { data: replicas = [] } = useGetReplicasQuery();
  const [recoverRepository, { isLoading: submitting }] = useRecoverRepositoryMutation();

  const projectOptions: Option[] = projects.map((p: ProjectDto) => ({ value: p.name, label: p.name }));
  const repoOptions: Option[] = repos.map((r: RepoDto) => ({ value: r.name, label: r.name }));
  const sourceOptions: SourceOption[] = replicas.map((replica: ReplicaInfo) => ({
    value: replica.serverId,
    label: `Server ${replica.serverId} — ${replica.host}${replica.current ? ' (this server)' : ''}`,
    host: replica.host,
  }));

  const complete = project != null && repo != null && source != null && fromRevision >= 2;

  const handleOpen = () => {
    setErrorMessage(null);
    onOpen();
  };

  const handleConfirm = async () => {
    if (!complete) {
      return;
    }
    try {
      const response = await recoverRepository({
        projectName: project.value,
        repoName: repo.value,
        fromRevision,
        sourceServerId: source.value,
      }).unwrap();
      setLastResult({ target: `${project.value}/${repo.value}`, response });
      if (response.status === 'COMPLETED') {
        dispatch(
          newNotification(
            'Repository recovered',
            `${project.value}/${repo.value} converged to revision ${response.headRevision} of server ` +
              `${source.value}. Verify it on the Repository Status page, then make it writable.`,
            'success',
          ),
        );
      } else {
        dispatch(
          newNotification(
            'Repository recovery requested',
            `Server ${source.value} originates the recovery of ${project.value}/${repo.value} ` +
              'asynchronously. Verify convergence on the Repository Status page, then make it writable.',
            'success',
          ),
        );
      }
      onClose();
      setRepo(null);
    } catch (error) {
      // Keep the modal open and show the reason inline; the toast alone is too transient for a
      // destructive operation.
      setErrorMessage(ErrorMessageParser.parse(error));
      dispatch(
        newNotification(
          `Failed to recover ${project.value}/${repo.value}`,
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
      <Heading size="md" mb="2">
        Recover a repository from a source replica
      </Heading>
      <Text fontSize="sm" color="gray.500" mb="4">
        Designates one replica&apos;s repository as the source of truth: every other replica resets to just
        before the start revision and replays the source&apos;s commits up to its head. The repository must
        be read-only first and stays read-only afterwards, until you make it writable on the Repository
        Status page.
      </Text>
      <Flex gap={4} align="flex-end" wrap="wrap">
        <FormControl maxW="xs">
          <FormLabel>Project</FormLabel>
          <Select<Option>
            id="recovery-project-select"
            name="recovery-project"
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
            id="recovery-repo-select"
            name="recovery-repo"
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
        <FormControl maxW="sm">
          <FormLabel>Source server (kept as-is)</FormLabel>
          <Select<SourceOption>
            id="recovery-source-select"
            name="recovery-source"
            options={sourceOptions}
            value={source}
            onChange={(option: SourceOption | null) => setSource(option)}
            isClearable
            placeholder="Select source server..."
            chakraStyles={controlStyles}
          />
        </FormControl>
        <FormControl maxW="3xs">
          <FormLabel>Start revision</FormLabel>
          <NumberInput
            min={2}
            value={fromRevision}
            onChange={(_, value) => setFromRevision(Number.isNaN(value) ? 0 : value)}
          >
            <NumberInputField name="recovery-from-revision" />
            <NumberInputStepper>
              <NumberIncrementStepper />
              <NumberDecrementStepper />
            </NumberInputStepper>
          </NumberInput>
          <FormHelperText>Replays this revision through the source head.</FormHelperText>
        </FormControl>
        <Button colorScheme="red" onClick={handleOpen} isDisabled={!complete}>
          Recover
        </Button>
      </Flex>
      <Alert status="info" borderRadius="md" fontSize="sm" mt="4">
        <AlertIcon />
        The repository must be read-only before recovery, so no new commit can be written while it runs.
        Encrypted repositories are not supported.
      </Alert>
      {lastResult && (
        <Alert status="success" borderRadius="md" fontSize="sm" mt="4">
          <AlertIcon />
          {lastResult.response.status === 'COMPLETED'
            ? `Recovery of ${lastResult.target} completed at revision ${lastResult.response.headRevision}. ` +
              'Verify it on the Repository Status page, then make it writable.'
            : `Recovery of ${lastResult.target} was requested; the source replica originates it ` +
              'asynchronously. Verify convergence on the Repository Status page, then make it writable.'}
        </Alert>
      )}
      {complete && (
        <RecoveryConfirmModal
          isOpen={isOpen}
          onClose={onClose}
          projectName={project.value}
          repoName={repo.value}
          fromRevision={fromRevision}
          sourceServerId={source.value}
          sourceHost={source.host}
          onConfirm={handleConfirm}
          isLoading={submitting}
          errorMessage={errorMessage}
        />
      )}
    </Box>
  );
};

export default RecoverRepositoryForm;
