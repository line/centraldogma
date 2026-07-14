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
  FormControl,
  FormErrorMessage,
  Input,
  Popover,
  PopoverArrow,
  PopoverBody,
  PopoverCloseButton,
  PopoverContent,
  PopoverFooter,
  PopoverHeader,
  PopoverTrigger,
  Spacer,
  useDisclosure,
} from '@chakra-ui/react';
import { useForm } from 'react-hook-form';
import { IoMdArrowDropdown } from 'react-icons/io';
import { useAddGroupMutation } from 'dogma/features/xds/xdsApiSlice';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';

type FormData = {
  groupId: string;
};

// A group id is also a repository name, so it follows the same naming rule.
// Dots are allowed (e.g. "my.group"), but slashes are not.
const GROUP_ID_PATTERN = /^[a-z](?:[a-z0-9_.-]*[a-z0-9])?$/;

export const NewGroup = () => {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>();
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const [addGroup, { isLoading }] = useAddGroupMutation();

  const onSubmit = async (data: FormData) => {
    try {
      await addGroup({ groupId: data.groupId }).unwrap();
      dispatch(newNotification('Group created', `Group '${data.groupId}' is created`, 'success'));
      reset();
      onClose();
    } catch (error) {
      dispatch(newNotification('Failed to create a group', ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
      <PopoverTrigger>
        <Button colorScheme="teal" size="sm" onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
          New Group
        </Button>
      </PopoverTrigger>
      <PopoverContent minWidth="md">
        <PopoverHeader pt={4} fontWeight="bold" border={0} mb={3}>
          Create a new group
        </PopoverHeader>
        <PopoverArrow />
        <PopoverCloseButton />
        <form onSubmit={handleSubmit(onSubmit)}>
          <PopoverBody minWidth="max-content">
            <FormControl isInvalid={errors.groupId ? true : false} isRequired>
              <Input
                id="groupId"
                placeholder="Enter group ID ..."
                {...register('groupId', { required: true, pattern: GROUP_ID_PATTERN })}
              />
              {errors.groupId && (
                <FormErrorMessage>
                  Group ID must match the pattern [a-z](?:[a-z0-9_.-]*[a-z0-9])? (lowercase letters, digits,
                  hyphens, underscores, and dots are allowed)
                </FormErrorMessage>
              )}
            </FormControl>
          </PopoverBody>
          <PopoverFooter border="0" display="flex" alignItems="center" justifyContent="space-between" pb={4}>
            <Spacer />
            <Button type="submit" colorScheme="teal" isLoading={isLoading} loadingText="Creating">
              Create
            </Button>
          </PopoverFooter>
        </form>
      </PopoverContent>
    </Popover>
  );
};
