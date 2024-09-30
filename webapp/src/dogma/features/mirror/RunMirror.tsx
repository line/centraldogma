/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

import { useRunMirrorMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import { useAppDispatch } from 'dogma/hooks';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { MirrorResult } from './MirrorResult';
import { MirrorDto } from '../project/settings/mirrors/MirrorDto';
import { FaPlay } from 'react-icons/fa';
import { IconButton } from '@chakra-ui/react';

export const RunMirror = ({ mirror }: { mirror: MirrorDto }) => {
  const [runMirror, { isLoading }] = useRunMirrorMutation();
  const dispatch = useAppDispatch();
  const onClick = async () => {
    try {
      const response: any = await runMirror({ projectName: mirror.projectName, id: mirror.id }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      const result: MirrorResult = response;
      if (result.mirrorStatus === 'SUCCESS') {
        dispatch(
          newNotification(`Mirror ${mirror.id} is performed successfully`, result.description, 'success'),
        );
      } else if (result.mirrorStatus === 'UP_TO_DATE') {
        dispatch(newNotification(`No changes`, result.description, 'info'));
      }
    } catch (error) {
      dispatch(newNotification(`Failed to run mirror ${mirror.id}`, ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <IconButton
      colorScheme="teal"
      size="sm"
      aria-label="Trigger mirror"
      onClick={onClick}
      isLoading={isLoading}
      icon={<FaPlay />}
    />
  );
};
