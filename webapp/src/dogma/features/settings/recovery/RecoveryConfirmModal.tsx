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
  Box,
  Badge,
  Code,
  Flex,
  Grid,
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
  toRevision: number;
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
  toRevision,
  sourceServerId,
  sourceHost,
  onConfirm,
  isLoading,
  errorMessage,
}: RecoveryConfirmModalProps): JSX.Element => {
  const target = `${projectName}/${repoName}`;
  const single = fromRevision === toRevision;

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
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      closeOnOverlayClick={!isLoading}
      closeOnEsc={!isLoading}
      size="2xl"
    >
      <ModalOverlay />
      <ModalContent>
        <ModalHeader fontSize="xl">Recover repository from a source replica</ModalHeader>
        <ModalCloseButton isDisabled={isLoading} />
        <ModalBody>
          <VStack align="stretch" spacing={3}>
            <Box borderWidth="1px" borderRadius="md" px={5} py={4}>
              <Grid
                templateColumns="max-content 1fr"
                columnGap={6}
                rowGap={3}
                fontSize="md"
                alignItems="center"
              >
                <Text fontWeight="semibold">Repository</Text>
                <Text fontFamily="mono" fontWeight="bold">
                  {target}
                </Text>
                <Text fontWeight="semibold">Source server</Text>
                <Text fontFamily="mono">
                  #{sourceServerId} ({sourceHost})
                </Text>
                <Text fontWeight="semibold">Replayed</Text>
                <Flex align="center" gap={3}>
                  <Text fontFamily="mono">
                    {single ? `r${fromRevision}` : `r${fromRevision} - r${toRevision}`}
                  </Text>
                  <Badge colorScheme="blue" variant="subtle">
                    {single ? '1 revision' : `${toRevision - fromRevision + 1} revisions`}
                  </Badge>
                </Flex>
                <Text fontWeight="semibold">New head</Text>
                <Text fontFamily="mono">{`r${toRevision}`}</Text>
                <Text fontWeight="semibold">Discarded</Text>
                <Text fontFamily="mono" fontWeight="semibold" color="red.500" _dark={{ color: 'red.300' }}>
                  {`r${toRevision + 1} and above`}
                </Text>
              </Grid>
            </Box>
            <Text fontSize="sm" color="gray.500">
              Applied on every replica, the source included.
            </Text>
            <Text>
              To confirm, type the full <Code>project/repository</Code> name below.
            </Text>
            <FormControl>
              <Input
                size="lg"
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
