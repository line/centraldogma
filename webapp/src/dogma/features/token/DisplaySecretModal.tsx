import {
  Button,
  HStack,
  IconButton,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
  Table,
  TableContainer,
  Tbody,
  Td,
  Text,
  Tr,
} from '@chakra-ui/react';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import { TokenDto } from 'dogma/features/token/TokenDto';
import { useAppDispatch } from 'dogma/hooks';
import { MdContentCopy } from 'react-icons/md';

export const DisplaySecretModal = ({
  isOpen,
  onClose,
  response,
}: {
  isOpen: boolean;
  onClose: () => void;
  response: TokenDto;
}) => {
  const dispatch = useAppDispatch();
  if (!response) return;
  const { appId, secret, systemAdmin, creation } = response;
  return (
    <Modal isOpen={isOpen} onClose={onClose} motionPreset="slideInBottom">
      <ModalOverlay />
      <ModalContent minWidth="max-content">
        <ModalHeader>Application token generated</ModalHeader>
        <ModalCloseButton />
        <ModalBody>
          <TableContainer minWidth="max-content">
            <Table whiteSpace="normal">
              <Tbody>
                <Tr>
                  <Td>Application ID</Td>
                  <Td>{appId}</Td>
                </Tr>
                <Tr>
                  <Td>Secret</Td>
                  <Td>
                    <HStack>
                      <Text>{secret}</Text>
                      <IconButton
                        aria-label="Copy to clipboard"
                        icon={<MdContentCopy />}
                        variant="ghost"
                        onClick={async () => {
                          await navigator.clipboard.writeText(secret);
                          dispatch(newNotification('', 'copied to clipboard', 'success'));
                        }}
                      />
                    </HStack>
                  </Td>
                </Tr>
                <Tr>
                  <Td>Level</Td>
                  <Td>{systemAdmin ? 'System Admin' : 'User'}</Td>
                </Tr>
                <Tr>
                  <Td>Created by</Td>
                  <Td>{creation.user}</Td>
                </Tr>
                <Tr>
                  <Td>Created at </Td>
                  <Td>
                    <DateWithTooltip date={creation.timestamp} />
                  </Td>
                </Tr>
              </Tbody>
            </Table>
          </TableContainer>
        </ModalBody>
        <ModalFooter>
          <Button colorScheme="teal" onClick={onClose}>
            OK
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
};
