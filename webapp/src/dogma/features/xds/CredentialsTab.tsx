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
  FormControl,
  FormLabel,
  HStack,
  Input,
  Tag,
  Text,
  useDisclosure,
} from '@chakra-ui/react';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { AiOutlineDelete } from 'react-icons/ai';
import { IoAddCircleOutline } from 'react-icons/io5';
import { createColumnHelper, getCoreRowModel, getSortedRowModel, useReactTable } from '@tanstack/react-table';
import { useMemo, useState } from 'react';
import { DataTable } from 'dogma/features/xds/DataTable';
import { DeleteConfirmationModal } from 'dogma/common/components/DeleteConfirmationModal';
import { Loading } from 'dogma/common/components/Loading';
import {
  useAddCredentialMutation,
  useDeleteCredentialMutation,
  useListCredentialsQuery,
} from 'dogma/features/xds/xdsApiSlice';
import { XdsCredentialDto } from 'dogma/features/xds/CredentialDto';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';

const columnHelper = createColumnHelper<XdsCredentialDto>();

export const CredentialsTab = ({ group }: { group: string }) => {
  const dispatch = useAppDispatch();
  const { data, isLoading, error } = useListCredentialsQuery({ group }, { refetchOnMountOrArgChange: true });
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [target, setTarget] = useState('');
  const [newId, setNewId] = useState('');
  const [newToken, setNewToken] = useState('');
  const [addCredential, { isLoading: isAdding }] = useAddCredentialMutation();
  const [deleteCredential, { isLoading: isDeleting }] = useDeleteCredentialMutation();

  const handleAdd = async () => {
    if (!newId.trim() || !newToken.trim()) {
      dispatch(newNotification('Missing fields', 'Credential ID and access token are required', 'error'));
      return;
    }
    try {
      await addCredential({ group, credentialId: newId.trim(), accessToken: newToken.trim() }).unwrap();
      dispatch(newNotification('Credential created', `Credential '${newId.trim()}' is created`, 'success'));
      setNewId('');
      setNewToken('');
    } catch (err) {
      dispatch(newNotification('Failed to create the credential', ErrorMessageParser.parse(err), 'error'));
    }
  };

  const handleDelete = async () => {
    try {
      await deleteCredential({ group, id: target }).unwrap();
      dispatch(newNotification('Credential deleted', `Credential '${target}' is deleted`, 'success'));
      onClose();
    } catch (err) {
      dispatch(newNotification('Failed to delete the credential', ErrorMessageParser.parse(err), 'error'));
    }
  };

  const columns = useMemo(
    () => [
      columnHelper.accessor('id', { header: 'ID', cell: (info) => info.getValue() }),
      columnHelper.accessor('type', {
        header: 'Type',
        cell: (info) => <Tag colorScheme="teal">{info.getValue()}</Tag>,
      }),
      columnHelper.accessor('id', {
        id: 'action',
        header: 'Action',
        enableSorting: false,
        cell: (info) => (
          <Button
            leftIcon={<AiOutlineDelete />}
            colorScheme="red"
            variant="ghost"
            size="sm"
            onClick={() => {
              setTarget(info.getValue());
              onOpen();
            }}
          >
            Delete
          </Button>
        ),
      }),
    ],
    [onOpen],
  );

  // Only access token credentials are surfaced for now. Memoized so the table receives a stable data
  // reference across re-renders (e.g. while typing in the add form), avoiding react-table re-render churn.
  const credentials = useMemo(() => (data || []).filter((c) => c.type === 'ACCESS_TOKEN'), [data]);
  const table = useReactTable({
    data: credentials,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });

  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    const status = (error as FetchBaseQueryError).status;
    if (status === 403) {
      return (
        <Alert status="info" borderRadius="md">
          <AlertIcon />
          Managing credentials requires the ADMIN role on this group.
        </Alert>
      );
    }
    return (
      <Alert status="error" borderRadius="md">
        <AlertIcon />
        Failed to load credentials.
      </Alert>
    );
  }

  return (
    <Box>
      <Box borderWidth="1px" borderRadius="md" p={4} mb={6} maxW="2xl">
        <Text fontWeight="bold" mb={3}>
          Add an access token credential
        </Text>
        <HStack align="flex-end" spacing={3}>
          <FormControl isRequired>
            <FormLabel fontSize="sm">Credential ID</FormLabel>
            <Input
              size="sm"
              placeholder="e.g. my-token"
              value={newId}
              onChange={(e) => setNewId(e.target.value)}
            />
          </FormControl>
          <FormControl isRequired>
            <FormLabel fontSize="sm">Access token</FormLabel>
            <Input
              size="sm"
              type="password"
              placeholder="access token"
              value={newToken}
              onChange={(e) => setNewToken(e.target.value)}
            />
          </FormControl>
          <Button
            colorScheme="teal"
            size="sm"
            leftIcon={<IoAddCircleOutline />}
            onClick={handleAdd}
            isLoading={isAdding}
            loadingText="Adding"
            flexShrink={0}
          >
            Add
          </Button>
        </HStack>
      </Box>

      {credentials.length === 0 ? (
        <Text color="gray.500">No access token credentials in this group yet.</Text>
      ) : (
        <DataTable table={table} />
      )}

      <DeleteConfirmationModal
        isOpen={isOpen}
        onClose={onClose}
        type="credential"
        id={target}
        from={group}
        handleDelete={handleDelete}
        isLoading={isDeleting}
      />
    </Box>
  );
};
