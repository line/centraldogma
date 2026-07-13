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
  Code,
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
  useClipboard,
  useColorMode,
  useDisclosure,
} from '@chakra-ui/react';
import { OptionBase, Select } from 'chakra-react-select';
import Prism from 'prismjs';
import 'prismjs/components/prism-bash';
import 'prismjs/themes/prism.css';
import { useEffect, useState } from 'react';
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

interface RecoveryResult {
  projectName: string;
  repoName: string;
  sourceServerId: number;
  response: RecoverRepositoryResponse;
}

/**
 * Returns the reason of a rejected recovery without the Java stack trace the server sends to a system
 * administrator, which would otherwise bury the message that matters.
 */
export function conciseErrorMessage(error: unknown): string {
  const parsed = ErrorMessageParser.parse(error);
  const lines: string[] = [];
  for (const line of parsed.split('\n')) {
    // A stack frame, or the exception class repeating the message that was already shown.
    if (/^\s+at\s/.test(line) || /^\s*(?:[a-z][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error)\b/.test(line)) {
      break;
    }
    lines.push(line);
  }
  const concise = lines.join('\n').trim();
  return concise || parsed;
}

/**
 * Builds a copy-pastable shell script that compares the head revision of the recovered repository on
 * every replica. A REQUESTED recovery completes asynchronously and its failure is only reported in the
 * source replica's log, so this is how an administrator verifies convergence before making the
 * repository writable again.
 *
 * <p>Over HTTPS each curl adds {@code -k}: it reaches a replica by its own host name, which a
 * certificate issued for the load balancer's name does not cover.
 */
export function buildVerificationScript(result: RecoveryResult, replicas: ReplicaInfo[]): string {
  const { projectName, repoName, sourceServerId } = result;
  const origin = process.env.NEXT_PUBLIC_HOST || window.location.origin;
  const url = new URL(origin);
  const https = url.protocol === 'https:';
  // Without an explicit port, assume 443 for https and the Central Dogma default port for http.
  const port = url.port || (https ? '443' : '36462');
  const lines = [
    'CD_TOKEN=<paste your access token>',
    `# Every replica must report the same head revision of ${projectName}/${repoName} as the source ` +
      `(server ${sourceServerId}).`,
    ...(https
      ? ['# -k skips certificate verification, since each replica is reached by its own host name.']
      : []),
    `# Adjust the port if a replica does not listen on :${port}.`,
    ...replicas.map((replica) => {
      const marker = replica.serverId === sourceServerId ? ' (source)' : '';
      return (
        `curl -s${https ? 'k' : ''} -H "Authorization: Bearer $CD_TOKEN" ` +
        `${url.protocol}//${replica.host}:${port}/api/v1/projects/${projectName}/repos/${repoName}` +
        ` | jq .headRevision  # server ${replica.serverId}${marker}`
      );
    }),
    `# A failed recovery is only reported in the log of the source replica (server ${sourceServerId}).`,
  ];
  return lines.join('\n');
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
  const [lastResult, setLastResult] = useState<RecoveryResult | null>(null);

  const { data: projects = [], isLoading: projectsLoading } = useGetProjectsQuery({ systemAdmin: false });
  const { data: repos = [], isFetching: reposFetching } = useGetReposQuery(project?.value ?? '', {
    skip: !project,
  });
  // Recovery is an incident-time tool: never trust a session-old cached roster.
  const { data: replicas = [] } = useGetReplicasQuery(undefined, { refetchOnMountOrArgChange: true });
  const [recoverRepository, { isLoading: submitting }] = useRecoverRepositoryMutation();

  const projectOptions: Option[] = projects.map((p: ProjectDto) => ({ value: p.name, label: p.name }));
  const repoOptions: Option[] = repos
    // Internal repositories cannot be recovered: their content is written by content transformers
    // without text normalization, so a replay cannot reproduce it byte-identically.
    .filter((r: RepoDto) => r.name !== 'meta' && r.name !== 'dogma')
    .map((r: RepoDto) => ({ value: r.name, label: r.name }));
  const sourceOptions: SourceOption[] = replicas.map((replica: ReplicaInfo) => ({
    value: replica.serverId,
    label: `Server ${replica.serverId} — ${replica.host}${replica.current ? ' (this server)' : ''}`,
    host: replica.host,
  }));

  const complete = project != null && repo != null && source != null && fromRevision >= 2;

  const verificationScript =
    lastResult != null && lastResult.response.status === 'REQUESTED'
      ? buildVerificationScript(lastResult, replicas)
      : '';
  const { onCopy, hasCopied, setValue: setClipboardValue } = useClipboard(verificationScript);
  // useClipboard captures only the initial value; track the current script.
  useEffect(() => setClipboardValue(verificationScript), [verificationScript, setClipboardValue]);

  const handleOpen = () => {
    // Clear the previous attempt's feedback so a stale banner cannot describe a different target.
    setErrorMessage(null);
    setLastResult(null);
    onOpen();
  };

  const handleConfirm = async () => {
    if (!complete || submitting) {
      return;
    }
    try {
      const response = await recoverRepository({
        projectName: project.value,
        repoName: repo.value,
        fromRevision,
        sourceServerId: source.value,
      }).unwrap();
      setLastResult({
        projectName: project.value,
        repoName: repo.value,
        sourceServerId: source.value,
        response,
      });
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
            `Server ${source.value} was asked to originate the recovery of ` +
              `${project.value}/${repo.value} asynchronously (best effort). Confirm the repository head ` +
              'matches the source before making it writable; a failure is only reported in the source ' +
              "replica's log.",
            'success',
          ),
        );
      }
      onClose();
      setRepo(null);
    } catch (error) {
      // Keep the modal open and show the reason inline; the toast alone is too transient for a
      // destructive operation.
      const reason = conciseErrorMessage(error);
      setErrorMessage(reason);
      dispatch(newNotification(`Failed to recover ${project.value}/${repo.value}`, reason, 'error'));
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
        before the start revision and replays the source&apos;s commits up to its head. The repository must be
        read-only first and stays read-only afterwards, until you make it writable on the Repository Status
        page.
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
            ? `Recovery of ${lastResult.projectName}/${lastResult.repoName} completed at revision ` +
              `${lastResult.response.headRevision}. Verify it on the Repository Status page, then make ` +
              'it writable.'
            : `Recovery of ${lastResult.projectName}/${lastResult.repoName} was requested; the source ` +
              'replica originates it asynchronously (best effort). Verify with the script below that ' +
              'every replica reports the same head revision before making the repository writable — a ' +
              "failure is only reported in the source replica's log."}
        </Alert>
      )}
      {lastResult && lastResult.response.status === 'REQUESTED' && (
        <Box mt="2">
          <Flex justify="space-between" align="center" mb="1">
            <Text fontSize="sm" fontWeight="bold">
              Check the head revision of every replica:
            </Text>
            <Button size="xs" onClick={onCopy}>
              {hasCopied ? 'Copied' : 'Copy'}
            </Button>
          </Flex>
          <Code
            as="pre"
            data-testid="recovery-verification-script"
            display="block"
            whiteSpace="pre"
            overflowX="auto"
            p="3"
            fontSize="xs"
            borderRadius="md"
            dangerouslySetInnerHTML={{
              __html: Prism.highlight(verificationScript, Prism.languages.bash, 'bash'),
            }}
          />
        </Box>
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
