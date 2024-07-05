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
import { MirrorDto } from 'dogma/features/mirror/MirrorDto';

let mirrors: MirrorDto[] = [];
for (let i = 0; i < 10; i++) {
  mirrors.push({
    id: `mirror-${i}`,
    projectName: `project-${i}`,
    credentialId: `credential-${i}`,
    direction: 'REMOTE_TO_LOCAL',
    enabled: true,
    gitignore: `ignore${i}`,
    localPath: `/local/path/${i}`,
    localRepo: `local-repo-${i}`,
    remotePath: `/remote/path/${i}`,
    remoteScheme: 'git+https',
    remoteBranch: 'master',
    remoteUrl: 'github.com:line/centraldogma',
    schedule: `${i} * * * * ?`,
  });
}

let revision = mirrors.length + 2;
export default function handler(req: NextApiRequest, res: NextApiResponse) {
  switch (req.method) {
    case 'GET':
      const projectName = req.query.projectName as string;
      mirrors = mirrors.map((mirror) => {
        mirror.projectName = projectName;
        return mirror;
      });
      res.status(200).json(mirrors);
      break;
    case 'POST':
      mirrors.push(req.body);
      res.status(201).json(`${++revision}`);
      break;
    default:
      res.setHeader('Allow', ['GET', 'POST']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
