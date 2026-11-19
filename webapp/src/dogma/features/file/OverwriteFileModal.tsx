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
  Button,
  Code,
  HStack,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
} from '@chakra-ui/react';

export const OverwriteFileModal = ({
  isOpen,
  onClose,
  path,
  onOverwrite,
  isLoading,
}: {
  isOpen: boolean;
  onClose: () => void;
  path: string;
  onOverwrite: () => void;
  isLoading: boolean;
}) => {
  return (
    <Modal isOpen={isOpen} onClose={onClose}>
      <ModalOverlay />
      <ModalContent>
        <ModalHeader>File already exists</ModalHeader>
        <ModalCloseButton />
        <ModalBody>
          A file with the same name already exists at <Code>{path}</Code>. Do you want to overwrite it?
        </ModalBody>
        <ModalFooter>
          <HStack spacing={3}>
            <Button colorScheme="red" variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button colorScheme="red" onClick={onOverwrite} isLoading={isLoading} loadingText="Overwriting">
              Overwrite
            </Button>
          </HStack>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
};
