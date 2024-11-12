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
import { newRandomCredential } from 'pages/api/v1/projects/[projectName]/credentials/index';
import { CredentialDto } from 'dogma/features/project/settings/credentials/CredentialDto';

const credentials: Map<string, CredentialDto> = new Map();

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const id = req.query.id as string;
  switch (req.method) {
    case 'GET':
      const credential = newRandomCredential(id);
      res.status(200).json(credential);
      break;
    case 'PUT':
      credentials.set(id, req.body);
      res.status(201).json(`${credentials.size + 2}`);
      break;
    default:
      res.setHeader('Allow', ['GET', 'PUT']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
