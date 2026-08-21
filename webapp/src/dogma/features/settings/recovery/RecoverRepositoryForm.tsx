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
  Badge,
  Box,
  Button,
  ButtonGroup,
  Code,
  Flex,
  FormControl,
  FormHelperText,
  FormLabel,
  Heading,
  Icon,
  NumberDecrementStepper,
  NumberIncrementStepper,
  NumberInput,
  NumberInputField,
  NumberInputStepper,
  Spacer,
  Table,
  TableContainer,
  Tbody,
  Td,
  Text,
  Th,
  Thead,
  Tr,
  useClipboard,
  useColorMode,
  useDisclosure,
} from '@chakra-ui/react';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { MdOpenInNew } from 'react-icons/md';
import { OptionBase, Select } from 'chakra-react-select';
import { format } from 'date-fns';
import Prism from 'prismjs';
import 'prismjs/components/prism-bash';
import 'prismjs/themes/prism.css';
import { ReactNode, useEffect, useState } from 'react';
import {
  useGetProjectsQuery,
  useGetReplicasQuery,
  useGetHistoryQuery,
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
  // The revision asked for, which the REQUESTED response does not echo back.
  toRevision: number;
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
 * Builds a copy-pastable shell script that compares the head of the recovered repository on every
 * replica. The replicas other than the source apply the recovery when they replay it, and a failure is
 * only reported in the source replica's log, so this is how an administrator confirms convergence before
 * making the repository writable again.
 *
 * <p>It compares the head *tree ID*, not the revision and not the commit ID: diverged replicas always
 * report the same revision, and replicas of a metadata repository report different commit IDs even when
 * their content is identical, because they wrote their early commits locally.
 *
 * <p>Over HTTPS each curl adds {@code -k}: it reaches a replica by its own host name, which a
 * certificate issued for the load balancer's name does not cover.
 */
export function buildVerificationScript(result: RecoveryResult, replicas: ReplicaInfo[]): string {
  const { projectName, repoName, sourceServerId, toRevision } = result;
  const origin = process.env.NEXT_PUBLIC_HOST || window.location.origin;
  const url = new URL(origin);
  const https = url.protocol === 'https:';
  // The roster carries no port, so every address is seeded from the URL this page was served on and the
  // operator corrects the rest. When that collapses two replicas onto one address, the check would poll the
  // same server twice and report a convergence it never checked, so say so rather than let it read as a pass.
  const port = url.port || (https ? '443' : '36462');
  const collided = new Set(replicas.map((replica) => replica.host)).size < replicas.length;
  // One call per replica, spelled out: a loop over a variable would poll the whole list as a single address
  // in zsh, which does not split an unquoted expansion into words. The dogma CLI has no command for this -
  // its commit type carries no commit ID - so the head endpoint is called directly.
  return [
    "CD_TOKEN='<paste a system administrator token>'",
    `TO_REVISION=${toRevision}  # the new head this recovery asked for`,
    // Both are reset here so that re-running this in the same shell - which is how an operator waits for
    // the replicas to converge - does not compare against the previous run's leftovers.
    "SEEN=''",
    "SOURCE_TREE=''",
    '',
    `# Every replica of ${projectName}/${repoName} must report "reset ok" and the same treeId, which is`,
    '# the fingerprint of the content alone. The revision proves nothing - a diverged replica reports the',
    '# same revision as the source - and neither does the commitId, which differs between replicas of a',
    '# metadata repository even when their content is identical.',
    '# REQUEST FAILED is not a pass either: that replica was never reached, and a failed recovery is',
    `# logged only on the source (server ${sourceServerId}).`,
    'head_of() {',
    '  case " $SEEN " in',
    '    *" $2 "*)',
    '      printf "server %s  %s  DUPLICATE ADDRESS - fix its port and re-run\\n" "$1" "$2"',
    '      return ;;',
    '  esac',
    '  SEEN="$SEEN $2"',
    `  head=$(curl -sf${https ? 'k' : ''} -m 10 -H "Authorization: Bearer $CD_TOKEN" \\`,
    `    "${url.protocol}//$2/api/v1/projects/${projectName}/repos/${repoName}/head" | tr -d " ")`,
    '  # -f prints nothing when the request is rejected, and the pipe hides the exit status of curl.',
    '  if [ -z "$head" ]; then',
    '    printf "server %s  %s  REQUEST FAILED\\n" "$1" "$2"',
    '    return',
    '  fi',
    '  case "$head" in',
    '    *"\\"revision\\":$TO_REVISION,"*) reset="reset ok" ;;',
    '    *) reset="NOT r$TO_REVISION" ;;',
    '  esac',
    '  tree=${head##*\'"treeId":"\'}',
    "  tree=${tree%%'\"'*}",
    '  [ -n "$SOURCE_TREE" ] || SOURCE_TREE=$tree',
    '  if [ "$tree" = "$SOURCE_TREE" ]; then',
    '    same="same tree"',
    '  else',
    '    same="DIFFERENT TREE"',
    '  fi',
    '  printf "server %s  %s  %-9s %-14s %s\\n" "$1" "$2" "$reset" "$same" "$head"',
    '}',
    ...(collided
      ? [
          '# WARNING: replicas share a host, so they were all given the same port and some lines below now',
          '#          point at the SAME server. Give each its own port first, or this polls one replica',
          '#          twice and wrongly looks converged.',
        ]
      : ['# The roster carries no port, so the ports are a guess. Correct any that differs.']),
    ...[...replicas]
      .sort((a, b) => Number(b.serverId === sourceServerId) - Number(a.serverId === sourceServerId))
      .map(
        (replica) =>
          `head_of ${replica.serverId} ${replica.host}:${port}` +
          (replica.serverId === sourceServerId ? '  # source, polled first' : ''),
      ),
  ].join('\n');
}

const StepNumber = ({ children }: { children: ReactNode }) => (
  <Flex
    align="center"
    justify="center"
    flexShrink={0}
    minW="6"
    h="6"
    borderRadius="full"
    bg="blue.500"
    color="white"
    fontSize="sm"
    fontWeight="bold"
  >
    {children}
  </Flex>
);

const RecoverRepositoryForm = () => {
  const { colorMode } = useColorMode();
  const replayedRowBg = colorMode === 'light' ? 'blue.50' : 'blue.900';
  const linkColor = colorMode === 'light' ? 'blue.600' : 'blue.300';
  // Green, as on the status badge that the operator is being sent to flip.
  const writableColor = colorMode === 'light' ? 'green.600' : 'green.300';
  const { isOpen, onOpen, onClose } = useDisclosure();
  const dispatch = useAppDispatch();

  const [project, setProject] = useState<Option | null>(null);
  const [repo, setRepo] = useState<Option | null>(null);
  const [source, setSource] = useState<SourceOption | null>(null);
  const { data: history, isFetching: historyFetching } = useGetHistoryQuery(
    {
      projectName: project?.value ?? '',
      repoName: repo?.value ?? '',
      revision: 'head',
      filePath: '/**',
      to: 1,
      maxCommits: 10,
    },
    { skip: project == null || repo == null },
  );
  const [fromRevision, setFromRevision] = useState(2);
  const [toRevision, setToRevision] = useState(2);
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
  const repoOptions: Option[] = repos.map((r: RepoDto) => ({ value: r.name, label: r.name }));
  const selectedRepo = repos.find((r: RepoDto) => r.name === repo?.value);
  const sourceOptions: SourceOption[] = replicas.map((replica: ReplicaInfo) => ({
    value: replica.serverId,
    label: `Server ${replica.serverId} — ${replica.host}${replica.current ? ' (this server)' : ''}`,
    host: replica.host,
  }));

  const readOnly = selectedRepo?.status === 'READ_ONLY';
  const rangeError =
    fromRevision < 2
      ? 'From revision must be at least 2: revision 1 creates the repository and carries no change.'
      : toRevision < fromRevision
        ? 'To revision must be at or after From revision.'
        : null;
  const complete =
    project != null &&
    repo != null &&
    source != null &&
    readOnly &&
    fromRevision >= 2 &&
    toRevision >= fromRevision;

  // Neither outcome means the cluster converged: the replicas other than the source apply the recovery
  // when they replay it. So the verification script belongs to both.
  const verificationScript = lastResult != null ? buildVerificationScript(lastResult, replicas) : '';
  const { onCopy, hasCopied, setValue: setClipboardValue } = useClipboard(verificationScript);
  // useClipboard captures only the initial value; track the current script.
  useEffect(() => setClipboardValue(verificationScript), [verificationScript, setClipboardValue]);

  const handleOpen = () => {
    setErrorMessage(null);
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
        toRevision,
        sourceServerId: source.value,
      }).unwrap();
      setLastResult({
        projectName: project.value,
        repoName: repo.value,
        sourceServerId: source.value,
        toRevision,
        response,
      });
      dispatch(
        newNotification(
          response.status === 'RECOVERING' ? 'Recovery originated' : 'Recovery requested',
          `Confirm every replica reports the source's head tree with the script below before making ` +
            `${project.value}/${repo.value} writable.`,
          'success',
        ),
      );
      onClose();
      setProject(null);
      setRepo(null);
      setSource(null);
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

  // Prism ships a light theme: its translucent white token backgrounds render as boxes on a dark
  // surface, and its comment and string colours are too dim to read there.
  const scriptStyles = {
    '.token.operator, .token.entity, .token.url': { background: 'transparent' },
    ...(colorMode === 'dark'
      ? {
          '.token.comment, .token.prolog, .token.doctype, .token.cdata': { color: 'gray.400' },
          '.token.string, .token.attr-value': { color: 'green.300' },
          '.token.operator, .token.entity, .token.url': { color: 'gray.300', background: 'transparent' },
        }
      : {}),
  };

  return (
    <Box borderWidth="1px" borderRadius="md" p="4" mb="8">
      <Heading size="md" mb="2">
        Recover a repository from a source replica
      </Heading>
      <Text fontSize="sm" color="gray.500" mb="4">
        Makes every replica, the source included, match the source&apos;s history.
      </Text>
      {/* The fields wrap on a narrow viewport, so the action lives on its own row and never ends up
          floating in the middle of a wrapped one. */}
      <Flex gap={4} align="flex-start" wrap="wrap">
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
              setFromRevision(2);
              setToRevision(2);
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
            onChange={(option: Option | null) => {
              setRepo(option);
              setFromRevision(2);
              setToRevision(2);
            }}
            isLoading={reposFetching}
            isDisabled={!project}
            isClearable
            isSearchable
            placeholder="Select repository..."
            chakraStyles={controlStyles}
          />
          <FormHelperText>Encrypted repositories cannot be recovered.</FormHelperText>
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
        <FormControl maxW="xs">
          <FormLabel>From revision</FormLabel>
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
          <FormHelperText>First revision replayed from the source.</FormHelperText>
        </FormControl>
        <FormControl maxW="xs">
          <FormLabel>To revision</FormLabel>
          <NumberInput
            min={2}
            value={toRevision}
            onChange={(_, value) => setToRevision(Number.isNaN(value) ? 0 : value)}
          >
            <NumberInputField name="recovery-to-revision" />
            <NumberInputStepper>
              <NumberIncrementStepper />
              <NumberDecrementStepper />
            </NumberInputStepper>
          </NumberInput>
          <FormHelperText>Last one replayed; becomes the new head everywhere.</FormHelperText>
        </FormControl>
      </Flex>
      {selectedRepo && (
        <Box mt="4">
          <Flex align="center" gap={2} mb="2">
            <Text fontSize="sm" fontWeight="semibold">
              {project?.value}/{selectedRepo.name}
            </Text>
            <Badge colorScheme={selectedRepo.status === 'READ_ONLY' ? 'orange' : 'green'}>
              {selectedRepo.status}
            </Badge>
          </Flex>
          <TableContainer>
            <Table size="sm" variant="simple">
              <Thead>
                <Tr>
                  <Th isNumeric>Revision</Th>
                  <Th>Summary</Th>
                  <Th>Author</Th>
                  <Th>Pushed at</Th>
                  <Th>Range</Th>
                </Tr>
              </Thead>
              <Tbody>
                {(history ?? []).map((commit) => {
                  const replayed = commit.revision >= fromRevision && commit.revision <= toRevision;
                  return (
                    <Tr key={commit.revision} bg={replayed ? replayedRowBg : undefined}>
                      <Td isNumeric fontFamily="mono">
                        {commit.revision}
                      </Td>
                      <Td>{commit.commitMessage.summary}</Td>
                      <Td>{commit.author.name}</Td>
                      <Td fontFamily="mono" whiteSpace="nowrap">
                        {format(new Date(commit.pushedAt), 'yyyy-MM-dd HH:mm:ss')}
                      </Td>
                      <Td>
                        <ButtonGroup size="xs" isAttached variant="outline">
                          <Button onClick={() => setFromRevision(commit.revision)}>From</Button>
                          <Button onClick={() => setToRevision(commit.revision)}>To</Button>
                        </ButtonGroup>
                      </Td>
                    </Tr>
                  );
                })}
              </Tbody>
            </Table>
          </TableContainer>
          <Text fontSize="xs" color="gray.500" mt="1">
            {historyFetching
              ? 'Loading the recent revisions…'
              : 'The ten most recent revisions; the highlighted ones are replayed.'}
          </Text>
        </Box>
      )}
      <Flex mt="4" gap="3" align="center" wrap="wrap">
        <Button colorScheme="red" onClick={handleOpen} isDisabled={!complete}>
          Recover
        </Button>
        {rangeError && (
          <Alert status="warning" borderRadius="md" fontSize="sm" py="2" w="auto">
            <AlertIcon />
            <Text>Recover is disabled: {rangeError}</Text>
          </Alert>
        )}
        {selectedRepo && selectedRepo.status !== 'READ_ONLY' && (
          <Alert status="warning" borderRadius="md" fontSize="sm" py="2" w="auto">
            <AlertIcon />
            <Text>
              Recover is disabled while this repository is writable. Make it read-only on the{' '}
              <ChakraLink
                href="/app/settings/repo-status"
                isExternal
                color={linkColor}
                fontWeight="semibold"
                textDecoration="underline"
              >
                Repository Status
                <Icon as={MdOpenInNew} boxSize={3} ml="1" verticalAlign="baseline" />
              </ChakraLink>{' '}
              page.
            </Text>
          </Alert>
        )}
      </Flex>
      {lastResult && (
        <Box mt="4" borderWidth="1px" borderRadius="md" p="4">
          <Alert status="success" borderRadius="md" fontSize="sm" mb="4">
            <AlertIcon />
            {lastResult.response.status === 'RECOVERING'
              ? `Server ${lastResult.sourceServerId} originated the recovery of ` +
                `${lastResult.projectName}/${lastResult.repoName} at revision ` +
                `${lastResult.toRevision}. The other replicas apply it asynchronously when they ` +
                'replay it, so the cluster has not converged yet.'
              : `Server ${lastResult.sourceServerId} was asked to originate the recovery of ` +
                `${lastResult.projectName}/${lastResult.repoName} asynchronously (best effort); a failure ` +
                "is only reported in that replica's log."}
          </Alert>
          <Flex align="center" gap="2" mb="2">
            <StepNumber>1</StepNumber>
            <Text fontSize="md" fontWeight="bold">
              Confirm the reset landed on every replica
            </Text>
            <Spacer />
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
            sx={scriptStyles}
            dangerouslySetInnerHTML={{
              __html: Prism.highlight(verificationScript, Prism.languages.bash, 'bash'),
            }}
          />
          <Flex align="center" gap="2" mt="4">
            <StepNumber>2</StepNumber>
            <Text fontSize="md" fontWeight="bold">
              Only once they all match, make {lastResult.projectName}/{lastResult.repoName}{' '}
              <Text as="span" color={writableColor}>
                writable
              </Text>{' '}
              again on the{' '}
              <ChakraLink
                href="/app/settings/repo-status"
                isExternal
                color={linkColor}
                fontWeight="bold"
                textDecoration="underline"
              >
                Repository Status
                <Icon as={MdOpenInNew} boxSize={3} ml="1" verticalAlign="baseline" />
              </ChakraLink>{' '}
              page
            </Text>
          </Flex>
        </Box>
      )}
      {complete && (
        <RecoveryConfirmModal
          isOpen={isOpen}
          onClose={onClose}
          projectName={project.value}
          repoName={repo.value}
          fromRevision={fromRevision}
          toRevision={toRevision}
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
