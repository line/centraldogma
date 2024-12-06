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

import { NextApiRequest, NextApiResponse } from 'next';
import _ from 'lodash';
import { CredentialDto } from 'dogma/features/project/settings/credentials/CredentialDto';
import { faker } from '@faker-js/faker';

const credentials: CredentialDto[] = _.range(0, 20).map(() =>
  newRandomCredential('credential-' + faker.random.word()),
);

export function newRandomCredential(id: string): CredentialDto {
  const index = id.length;
  switch (index % 4) {
    case 0:
      return {
        id,
        type: 'password',
        username: `username-${index}`,
        password: `password-${index}`,
        enabled: true,
      };
    case 1:
      return {
        id,
        type: 'public_key',
        username: `username-${index}`,
        publicKey: `public-key-${index}`,
        privateKey: `private-key-${index}`,
        passphrase: `passphrase-${index}`,
        enabled: true,
      };
    case 2:
      return {
        id,
        type: 'access_token',
        accessToken: `access-token-${index}`,
        enabled: true,
      };
    case 3:
      return {
        id,
        type: 'none',
        enabled: true,
      };
  }
}

let revision = credentials.length + 2;
export default function handler(req: NextApiRequest, res: NextApiResponse) {
  switch (req.method) {
    case 'GET':
      res.status(200).json(credentials);
      break;
    case 'POST':
      credentials.push(req.body);
      res.status(201).json(`${++revision}`);
      break;
    default:
      res.setHeader('Allow', ['GET', 'POST']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
