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
import { Badge, Box, Button, Flex, Heading, Spacer, Text, useDisclosure } from '@chakra-ui/react';
import { AiOutlineDelete } from 'react-icons/ai';
import { createColumnHelper, getCoreRowModel, getSortedRowModel, useReactTable } from '@tanstack/react-table';
import { useMemo, useState } from 'react';
import { DataTable } from 'dogma/features/xds/DataTable';
import { DeleteConfirmationModal } from 'dogma/common/components/DeleteConfirmationModal';
import { AddRepositoryRole } from 'dogma/features/xds/AddRepositoryRole';
import { RepositoryRole } from 'dogma/features/xds/MetadataDto';

export interface RoleEntry {
  id: string;
  role: RepositoryRole;
}

const ROLE_COLORS: Record<RepositoryRole, string> = {
  READ: 'green',
  WRITE: 'blue',
  ADMIN: 'purple',
};

const columnHelper = createColumnHelper<RoleEntry>();

export const RoleSection = ({
  title,
  entityLabel,
  group,
  entries,
  isAdding,
  isDeleting,
  onAdd,
  onDelete,
  options,
}: {
  title: string;
  entityLabel: string;
  group: string;
  entries: RoleEntry[];
  isAdding: boolean;
  isDeleting: boolean;
  onAdd: (id: string, role: RepositoryRole) => Promise<boolean>;
  onDelete: (id: string) => Promise<void>;
  // When provided, the id is selected from this dropdown instead of typed (e.g. app identities).
  options?: { value: string; label: string }[];
}) => {
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [target, setTarget] = useState('');

  const columns = useMemo(
    () => [
      columnHelper.accessor('id', { header: entityLabel }),
      columnHelper.accessor('role', {
        header: 'Role',
        cell: (info) => <Badge colorScheme={ROLE_COLORS[info.getValue()]}>{info.getValue()}</Badge>,
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
    [entityLabel, onOpen],
  );

  const table = useReactTable({
    data: entries,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });

  return (
    <Box mb={10}>
      <Flex alignItems="center" mb={2}>
        <Heading size="md">{title}</Heading>
        <Spacer />
        <AddRepositoryRole
          label={`Add ${entityLabel}`}
          placeholder={options ? `Select ${entityLabel} ...` : `Enter ${entityLabel} ...`}
          isLoading={isAdding}
          onAdd={onAdd}
          options={options}
        />
      </Flex>
      {entries.length === 0 ? (
        <Text color="gray.500">No {entityLabel.toLowerCase()} has been granted access yet.</Text>
      ) : null}
      <DataTable table={table} />
      <DeleteConfirmationModal
        isOpen={isOpen}
        onClose={onClose}
        type={entityLabel}
        id={target}
        from={group}
        handleDelete={async () => {
          await onDelete(target);
          onClose();
        }}
        isLoading={isDeleting}
      />
    </Box>
  );
};
