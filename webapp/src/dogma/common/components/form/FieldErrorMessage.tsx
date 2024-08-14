/*
 * Copyright 2023 LINE Corporation
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

import { FieldError } from 'react-hook-form';
import { FormErrorMessage } from '@chakra-ui/react';

interface FieldErrorMessageProps {
  error?: FieldError;
  fieldName?: string;
  errorMessage?: string;
}

const FieldErrorMessage = ({ error, fieldName, errorMessage }: FieldErrorMessageProps) => {
  if (error == null) {
    return null;
  }

  let message = errorMessage || error.message;
  if (!message) {
    // Fill the message for known errors.
    if (error.type === 'required') {
      message = `Please enter the ${fieldName || error.ref.name}`;
    } else if (error.type === 'pattern') {
      message = `An invalid pattern string`;
    }
  }

  if (message) {
    return <FormErrorMessage>{message}</FormErrorMessage>;
  } else {
    return null;
  }
};

export default FieldErrorMessage;
