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
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  Button,
  Flex,
  FormControl,
  FormLabel,
  Heading,
  HStack,
  Input,
  Link,
  Spacer,
  Tag,
  Text,
  useDisclosure,
  VStack,
} from '@chakra-ui/react';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { default as RouteLink } from 'next/link';
import Router from 'next/router';
import { useEffect, useMemo, useState } from 'react';
import { AiOutlineClose, AiOutlineDelete, AiOutlineEdit } from 'react-icons/ai';
import { FiSave } from 'react-icons/fi';
import { Deferred } from 'dogma/common/components/Deferred';
import { JsonEditor } from 'dogma/common/components/JsonEditor';
import { DeleteConfirmationModal } from 'dogma/common/components/DeleteConfirmationModal';
import {
  useCreateResourceMutation,
  useDeleteResourceMutation,
  useGetResourceQuery,
  useUpdateResourceMutation,
} from 'dogma/features/xds/xdsApiSlice';
import { XdsResourceType, XDS_RESOURCE_META, XDS_RESOURCE_TEMPLATES } from 'dogma/features/xds/XdsTypes';
import { extractReferences, referenceHref, resolveReference } from 'dogma/features/xds/xdsReferences';
import { ResourceGraph } from 'dogma/features/xds/ResourceGraph';
import { useGroupWriteAccess } from 'dogma/common/useGroupWriteAccess';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';

function parseJsonOrNotify(dispatch: ReturnType<typeof useAppDispatch>, value: string): object | null {
  try {
    return JSON.parse(value);
  } catch (e) {
    dispatch(newNotification('Invalid JSON', (e as Error).message, 'error'));
    return null;
  }
}

const NewResourceEditor = ({ group, type }: { group: string; type: XdsResourceType }) => {
  const meta = XDS_RESOURCE_META[type];
  const dispatch = useAppDispatch();
  const [id, setId] = useState('');
  const [content, setContent] = useState(XDS_RESOURCE_TEMPLATES[type]);
  const [createResource, { isLoading }] = useCreateResourceMutation();

  const handleCreate = async () => {
    if (!id) {
      dispatch(newNotification('ID is required', `Please enter the ${meta.label} ID`, 'error'));
      return;
    }
    if (parseJsonOrNotify(dispatch, content) === null) {
      return;
    }
    try {
      await createResource({ group, type, id, body: content }).unwrap();
      dispatch(newNotification(`${meta.label} created`, `${meta.label} '${id}' is created`, 'success'));
      Router.push(`/app/xds/group?name=${encodeURIComponent(group)}&type=${type}`);
    } catch (err) {
      dispatch(newNotification(`Failed to create the ${meta.label}`, ErrorMessageParser.parse(err), 'error'));
    }
  };

  return (
    <Box>
      <FormControl isRequired mb={4} maxW="md">
        <FormLabel>{meta.label} ID</FormLabel>
        <Input value={id} onChange={(e) => setId(e.target.value)} placeholder={`Enter ${meta.label} ID ...`} />
      </FormControl>
      <JsonEditor value={content} onChange={setContent} />
      <Flex mt={4}>
        <Spacer />
        <Button colorScheme="teal" leftIcon={<FiSave />} onClick={handleCreate} isLoading={isLoading}>
          Create
        </Button>
      </Flex>
    </Box>
  );
};

// Lists links to the child resources referenced by the given resource content (LDS -> RDS/CDS, RDS -> CDS,
// CDS -> EDS), letting the user jump straight to a referenced resource's view.
const ReferencePanel = ({
  group,
  type,
  content,
}: {
  group: string;
  type: XdsResourceType;
  content: string;
}) => {
  const references = useMemo(
    () => extractReferences(type, content).map((ref) => resolveReference(group, ref)),
    [group, type, content],
  );
  if (references.length === 0) {
    return null;
  }
  return (
    <Box borderWidth="1px" borderRadius="md" p={3} mb={3}>
      <Text fontWeight="bold" fontSize="sm" mb={2}>
        References
      </Text>
      <VStack align="stretch" spacing={1}>
        {references.map((ref) => (
          <HStack key={`${ref.targetType} ${ref.name}`} spacing={2}>
            <Tag size="sm" colorScheme="purple" flexShrink={0}>
              {XDS_RESOURCE_META[ref.targetType].acronym}
            </Tag>
            <Link as={RouteLink} href={referenceHref(ref)} color="teal" wordBreak="break-all">
              {ref.name}
            </Link>
          </HStack>
        ))}
      </VStack>
    </Box>
  );
};

const ExistingResourceEditor = ({
  group,
  type,
  id,
  k8s,
}: {
  group: string;
  type: XdsResourceType;
  id: string;
  k8s: boolean;
}) => {
  const meta = XDS_RESOURCE_META[type];
  const dispatch = useAppDispatch();
  // Edit/Delete are shown only to users with WRITE on the group. This also hides them when viewing an endpoint
  // (EDS) of a group the user cannot write to, since endpoints are readable regardless of permission.
  const { hasWrite } = useGroupWriteAccess(group);
  const { data, isLoading, error } = useGetResourceQuery(
    { group, type, id, k8s },
    { refetchOnMountOrArgChange: true },
  );
  const [content, setContent] = useState('');
  // A resource opens in read-only view; the user must click Edit to modify it (like the main web app).
  const [editing, setEditing] = useState(false);
  const [updateResource, { isLoading: isSaving }] = useUpdateResourceMutation();
  const [deleteResource, { isLoading: isDeleting }] = useDeleteResourceMutation();
  const { isOpen, onOpen, onClose } = useDisclosure();

  const originalContent = useMemo(() => {
    if (!data) {
      return '';
    }
    const raw = data.content !== undefined ? data.content : {};
    return typeof raw === 'string' ? raw : JSON.stringify(raw, null, 2);
  }, [data]);

  // Sync the editor to the latest fetched content, but never while the user is editing: a background
  // refetch (e.g. triggered by the coarse 'Resource' cache invalidation) must not clobber unsaved edits.
  useEffect(() => {
    if (!editing) {
      setContent(originalContent);
    }
  }, [originalContent, editing]);

  const handleSave = async () => {
    if (parseJsonOrNotify(dispatch, content) === null) {
      return;
    }
    try {
      await updateResource({ group, type, id, body: content }).unwrap();
      dispatch(newNotification(`${meta.label} updated`, `${meta.label} '${id}' is updated`, 'success'));
      setEditing(false);
    } catch (err) {
      dispatch(newNotification(`Failed to update the ${meta.label}`, ErrorMessageParser.parse(err), 'error'));
    }
  };

  const handleCancel = () => {
    setContent(originalContent);
    setEditing(false);
  };

  const handleDelete = async () => {
    try {
      await deleteResource({ group, type, id }).unwrap();
      dispatch(newNotification(`${meta.label} deleted`, `${meta.label} '${id}' is deleted`, 'success'));
      Router.push(`/app/xds/group?name=${encodeURIComponent(group)}&type=${type}`);
    } catch (err) {
      dispatch(newNotification(`Failed to delete the ${meta.label}`, ErrorMessageParser.parse(err), 'error'));
    }
  };

  // Resources generated by a k8s aggregator are managed by the aggregator, so they are always read-only
  // (no edit/save/delete). Otherwise the editor is read-only until the user clicks Edit.
  const readOnly = k8s || !editing;

  // A 404 means the resource does not exist (commonly reached by following a reference to a resource that was
  // never created or has been deleted). Show an explicit message instead of the raw server stack trace.
  const notFound = (error as FetchBaseQueryError | undefined)?.status === 404;

  return (
    <Deferred isLoading={isLoading} error={notFound ? undefined : error}>
      {() =>
        notFound ? (
          <Alert status="warning" borderRadius="md" alignItems="flex-start">
            <AlertIcon />
            <Box>
              <Text fontWeight="bold">{meta.label} not found</Text>
              <Text fontSize="sm">
                No {meta.label.toLowerCase()} named &apos;{id}&apos; exists in group &apos;{group}&apos;. A
                resource that references it may point to a {meta.label.toLowerCase()} that has not been created
                yet or has been deleted.
              </Text>
            </Box>
          </Alert>
        ) : (
          <Box>
            {k8s ? (
              <Alert status="info" borderRadius="md" mb={2}>
                <AlertIcon />
                This endpoint is generated by a Kubernetes aggregator and is read-only.
              </Alert>
            ) : (
              <Flex mb={2} align="center">
                {type !== 'endpoints' && <ResourceGraph group={group} type={type} id={id} k8s={k8s} />}
                <Spacer />
                {/* Mutating controls are shown only to users with WRITE on the group. */}
                {hasWrite &&
                  (editing ? (
                    <Button
                      variant="outline"
                      colorScheme="gray"
                      leftIcon={<AiOutlineClose />}
                      size="sm"
                      onClick={handleCancel}
                    >
                      Cancel
                    </Button>
                  ) : (
                    <HStack spacing={3}>
                      <Button
                        colorScheme="teal"
                        leftIcon={<AiOutlineEdit />}
                        size="sm"
                        onClick={() => setEditing(true)}
                      >
                        Edit
                      </Button>
                      <Button colorScheme="red" leftIcon={<AiOutlineDelete />} size="sm" onClick={onOpen}>
                        Delete
                      </Button>
                    </HStack>
                  ))}
              </Flex>
            )}
            <ReferencePanel group={group} type={type} content={originalContent} />
            <JsonEditor value={content} onChange={readOnly ? undefined : setContent} readOnly={readOnly} />
            {editing && hasWrite && (
              <Flex mt={4}>
                <Spacer />
                <Button colorScheme="teal" leftIcon={<FiSave />} onClick={handleSave} isLoading={isSaving}>
                  Save
                </Button>
              </Flex>
            )}
            <DeleteConfirmationModal
              isOpen={isOpen}
              onClose={onClose}
              type={meta.label}
              id={id}
              from={group}
              handleDelete={handleDelete}
              isLoading={isDeleting}
            />
          </Box>
        )
      }
    </Deferred>
  );
};

export const ResourceEditor = ({
  group,
  type,
  id,
  isNew,
  k8s = false,
}: {
  group: string;
  type: XdsResourceType;
  id?: string;
  isNew: boolean;
  k8s?: boolean;
}) => {
  const meta = XDS_RESOURCE_META[type];
  const title = isNew ? `New ${meta.label}` : id;
  return (
    <Box p="2">
      <Breadcrumb mb={4} color="gray.500">
        <BreadcrumbItem>
          <BreadcrumbLink as={RouteLink} href="/app/xds">
            Groups
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem>
          <BreadcrumbLink as={RouteLink} href={`/app/xds/group?name=${encodeURIComponent(group)}&type=${type}`}>
            {group}
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem>
          <BreadcrumbLink as={RouteLink} href={`/app/xds/group?name=${encodeURIComponent(group)}&type=${type}`}>
            {meta.label}s
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <BreadcrumbLink href="#">{title}</BreadcrumbLink>
        </BreadcrumbItem>
      </Breadcrumb>
      <Heading size="lg" mb={6}>
        <HStack color="teal">
          <Box>
            {meta.label} · {meta.acronym}
          </Box>
        </HStack>
      </Heading>
      {isNew || !id ? (
        <NewResourceEditor group={group} type={type} />
      ) : (
        <ExistingResourceEditor group={group} type={type} id={id} k8s={k8s} />
      )}
    </Box>
  );
};
