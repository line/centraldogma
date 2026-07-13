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

interface RecoveryConfirmModalProps {
  isOpen: boolean;
  onClose: () => void;
  projectName: string;
  repoName: string;
  fromRevision: number;
  sourceServerId: number;
  sourceHost: string;
  onConfirm: () => void;
  isLoading: boolean;
  errorMessage: string | null;
}

export const RecoveryConfirmModal = ({
  isOpen,
  onClose,
  projectName,
  repoName,
  fromRevision,
  sourceServerId,
  sourceHost,
  onConfirm,
  isLoading,
  errorMessage,
}: RecoveryConfirmModalProps): JSX.Element => {
  const target = `${projectName}/${repoName}`;

  const [typed, setTyped] = useState('');
  const matched = typed === target;

  // Start from an empty input every time the modal opens.
  useEffect(() => {
    if (isOpen) {
      setTyped('');
    }
  }, [isOpen, target]);

  const handleClose = () => {
    setTyped('');
    onClose();
  };

  return (
    <Modal isOpen={isOpen} onClose={handleClose} closeOnOverlayClick={!isLoading} closeOnEsc={!isLoading}>
      <ModalOverlay />
      <ModalContent>
        <ModalHeader>Recover repository from a source replica</ModalHeader>
        <ModalCloseButton isDisabled={isLoading} />
        <ModalBody>
          <VStack align="stretch" spacing={3}>
            <Text>
              This rewrites{' '}
              <Code fontWeight="bold" colorScheme="red">
                {target}
              </Code>{' '}
              on every replica other than the source (server {sourceServerId}, <Code>{sourceHost}</Code>): each
              one is reset to revision {fromRevision - 1} and replays the source&apos;s commits up to its head.
              The source repository itself is never modified.
            </Text>
            <Alert status="warning" borderRadius="md" fontSize="sm">
              <AlertIcon />
              <Text>
                Commits that exist only on a non-source replica are discarded. Make sure server {sourceServerId}{' '}
                really holds the history you want to keep.
              </Text>
            </Alert>
            <Text>
              To confirm, type the full <Code>project/repository</Code> name below.
            </Text>
            <FormControl>
              <Input
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                placeholder={target}
                aria-label="Type the full project/repository name to confirm"
                autoFocus
                autoComplete="off"
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && matched && !isLoading) {
                    onConfirm();
                  }
                }}
              />
            </FormControl>
            {errorMessage && (
              <Alert
                status="error"
                borderRadius="md"
                fontSize="sm"
                overflowWrap="anywhere"
                whiteSpace="pre-wrap"
                maxHeight="30vh"
                overflowY="auto"
                alignItems="flex-start"
              >
                <AlertIcon />
                {errorMessage}
              </Alert>
            )}
          </VStack>
        </ModalBody>
        <ModalFooter>
          <HStack spacing={3}>
            <Button variant="outline" onClick={handleClose} isDisabled={isLoading}>
              Cancel
            </Button>
            <Button
              colorScheme="red"
              onClick={onConfirm}
              isDisabled={!matched}
              isLoading={isLoading}
              loadingText="Recovering"
            >
              Recover
            </Button>
          </HStack>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
};
