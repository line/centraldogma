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
  Heading,
  Spacer,
  Text,
  useColorModeValue,
  useDisclosure,
} from '@chakra-ui/react';
import { AiOutlineDelete } from 'react-icons/ai';
import { useRouter } from 'next/router';
import { DeleteConfirmationModal } from 'dogma/common/components/DeleteConfirmationModal';
import { Loading } from 'dogma/common/components/Loading';
import { useDeleteGroupMutation } from 'dogma/features/xds/xdsApiSlice';
import { useGroupAdminAccess } from 'dogma/common/useGroupAdminAccess';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';

export const DangerZone = ({ group }: { group: string }) => {
  const dispatch = useAppDispatch();
  const router = useRouter();
  const { isLoading, isAdmin } = useGroupAdminAccess(group);
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [deleteGroup, { isLoading: isDeleting }] = useDeleteGroupMutation();
  const borderColor = useColorModeValue('red.200', 'red.700');

  const handleDelete = async () => {
    try {
      await deleteGroup({ groupId: group }).unwrap();
      dispatch(newNotification('Group deleted', `Group '${group}' is deleted`, 'success'));
      onClose();
      router.push('/app/xds');
    } catch (error) {
      dispatch(newNotification('Failed to delete the group', ErrorMessageParser.parse(error), 'error'));
    }
  };

  if (isLoading) {
    return <Loading />;
  }
  // Deleting a group requires the ADMIN role; non-admins reach this only via a direct link.
  if (!isAdmin) {
    return (
      <Alert status="info" borderRadius="md">
        <AlertIcon />
        Managing this group requires the ADMIN role.
      </Alert>
    );
  }

  return (
    <Box borderWidth="1px" borderColor={borderColor} borderRadius="md" p={5} maxW="2xl">
      <Heading size="sm" color="red.500" mb={3}>
        Delete this group
      </Heading>
      <Flex align="center">
        <Box pr={6}>
          <Text>
            Once a group is deleted, its repository and all of its xDS resources are removed. This action cannot
            be undone.
          </Text>
        </Box>
        <Spacer />
        <Button leftIcon={<AiOutlineDelete />} colorScheme="red" flexShrink={0} onClick={onOpen}>
          Delete group
        </Button>
      </Flex>
      <DeleteConfirmationModal
        isOpen={isOpen}
        onClose={onClose}
        type="group"
        id={group}
        handleDelete={handleDelete}
        isLoading={isDeleting}
      />
    </Box>
  );
};
