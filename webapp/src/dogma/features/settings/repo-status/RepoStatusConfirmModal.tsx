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
  Button,
  Code,
  FormControl,
  HStack,
  Input,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
  Text,
  VStack,
} from '@chakra-ui/react';
import { useEffect, useState } from 'react';
import { ReplicationStatus } from 'dogma/features/settings/repo-status/RepoStatusDto';

// A project-scoped read-only entry uses the internal "dogma" repository.
const PROJECT_SCOPE_REPO = 'dogma';

interface RepoStatusConfirmModalProps {
  isOpen: boolean;
  onClose: () => void;
  projectName: string;
  repoName: string;
  targetStatus: ReplicationStatus;
  onConfirm: () => void;
  isLoading: boolean;
}

export const RepoStatusConfirmModal = ({
  isOpen,
  onClose,
  projectName,
  repoName,
  targetStatus,
  onConfirm,
  isLoading,
}: RepoStatusConfirmModalProps): JSX.Element => {
  const target = `${projectName}/${repoName}`;
  const readOnly = targetStatus === 'READ_ONLY';
  const actionLabel = readOnly ? 'Make read-only' : 'Make writable';
  const colorScheme = readOnly ? 'red' : 'green';
  const projectScope = repoName === PROJECT_SCOPE_REPO;
  const scopeLabel = projectScope ? 'project' : 'repository';

  const [typed, setTyped] = useState('');
  const matched = typed === target;

  // Start from an empty input every time the modal opens. This also covers the
  // same component instance being reused for a different row (the list re-renders
  // and reflows after a status change) and the success path that closes without
  // going through handleClose.
  useEffect(() => {
    if (isOpen) {
      setTyped('');
    }
  }, [isOpen, target]);

  // Reset the typed confirmation whenever the modal is dismissed.
  const handleClose = () => {
    setTyped('');
    onClose();
  };

  return (
    <Modal isOpen={isOpen} onClose={handleClose} closeOnOverlayClick={!isLoading} closeOnEsc={!isLoading}>
      <ModalOverlay />
      <ModalContent>
        <ModalHeader>{`Make ${scopeLabel} ${readOnly ? 'read-only' : 'writable'}`}</ModalHeader>
        <ModalCloseButton isDisabled={isLoading} />
        <ModalBody>
          <VStack align="stretch" spacing={3}>
            <Text>
              This {readOnly ? 'blocks all writes to' : 'restores writes to'}{' '}
              <Code fontWeight="bold" colorScheme={colorScheme}>
                {target}
              </Code>
              . To confirm, type the full <Code>project/repository</Code> name below.
            </Text>
            {projectScope && (
              <Alert status={readOnly ? 'warning' : 'info'} borderRadius="md" fontSize="sm">
                <AlertIcon />
                {readOnly ? (
                  <Text>
                    <Code>{PROJECT_SCOPE_REPO}</Code> is the internal repository of project{' '}
                    <Code>{projectName}</Code>, so making it read-only blocks all writes to every repository in
                    project <Code>{projectName}</Code>.
                  </Text>
                ) : (
                  <Text>
                    This clears the project-wide read-only status of <Code>{projectName}</Code>. Repositories
                    that were made read-only individually stay read-only.
                  </Text>
                )}
              </Alert>
            )}
            <FormControl>
              <Input
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                placeholder={target}
                autoFocus
                autoComplete="off"
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && matched && !isLoading) {
                    onConfirm();
                  }
                }}
              />
            </FormControl>
          </VStack>
        </ModalBody>
        <ModalFooter>
          <HStack spacing={3}>
            <Button variant="outline" onClick={handleClose} isDisabled={isLoading}>
              Cancel
            </Button>
            <Button
              colorScheme={colorScheme}
              onClick={onConfirm}
              isDisabled={!matched}
              isLoading={isLoading}
              loadingText="Applying"
            >
              {actionLabel}
            </Button>
          </HStack>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
};
