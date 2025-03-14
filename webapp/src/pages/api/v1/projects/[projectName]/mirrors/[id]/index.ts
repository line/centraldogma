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
import { MirrorRequest } from 'dogma/features/repo/settings/mirrors/MirrorRequest';

const mirrors: Map<number, MirrorRequest> = new Map();

function newMirror(index: number, projectName: string): MirrorRequest {
  return {
    id: `mirror-${index}`,
    projectName: projectName,
    credentialName: `projects/${projectName}/credentials/credential-${index}`,
    direction: 'REMOTE_TO_LOCAL',
    enabled: true,
    gitignore: `ignore${index}`,
    localPath: `/local/path/${index}`,
    localRepo: `local-repo-${index}`,
    remotePath: `/remote/path/${index}`,
    remoteScheme: 'git+https',
    remoteBranch: 'master',
    remoteUrl: 'github.com:line/centraldogma',
    schedule: `${index} * * * * ?`,
  };
}

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const projectName = req.query.projectName as string;
  const index = parseInt(req.query.index as string, 10);
  switch (req.method) {
    case 'GET':
      const mirror = newMirror(index, projectName);
      mirrors.set(index, mirror);
      res.status(200).json(mirror);
      break;
    case 'PUT':
      mirrors.set(index, req.body);
      res.status(201).json(`${mirrors.size + 2}`);
      break;
    default:
      res.setHeader('Allow', ['GET', 'PUT']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
