import type { NextApiRequest, NextApiResponse } from 'next';
import { RepositoryRole } from 'dogma/features/auth/RepositoryRole';

const projectMetadata = {
  name: 'abcd',
  repos: {
    meta: {
      name: 'meta',
      roles: {
        projects: {
          member: 'READ' as RepositoryRole,
          guest: null as 'READ' | 'WRITE' | null,
        },
        users: {},
        tokens: {},
      },
      creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-23T03:13:50.128853Z' },
    },
    repo1: {
      name: 'repo1',
      roles: {
        projects: {
          member: 'WRITE' as RepositoryRole,
          guest: 'WRITE',
        },
        users: {
          'lz123456@localhost.localdomain': 'WRITE',
        },
        tokens: {
          'test-token': 'READ',
        },
      },
      creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-23T03:16:18.853509Z' },
    },
    repo2: {
      name: 'repo2',
      roles: {
        projects: {
          member: 'WRITE' as RepositoryRole,
          guest: null as 'READ' | 'WRITE' | null,
        },
        users: {},
        tokens: {},
      },
      creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-12-16T05:25:30.973209Z' },
      removal: { user: 'lb56789@localhost.localdomain', timestamp: '2022-12-16T05:25:37.020133Z' },
    },
  },
  members: {
    'lz123456@localhost.localdomain': {
      login: 'lz123456@localhost.localdomain',
      role: 'OWNER',
      creation: { user: 'lz123456@localhost.localdomain', timestamp: '2022-11-30T02:43:04.655753Z' },
    },
    'lb56789@localhost.localdomain': {
      login: 'lb56789@localhost.localdomain',
      role: 'MEMBER',
      creation: { user: 'lz123456@localhost.localdomain', timestamp: '2022-12-16T02:54:12.431395Z' },
    },
    'la88888@localhost.localdomain': {
      login: 'la88888@localhost.localdomain',
      role: 'MEMBER',
      creation: { user: 'lz123456@localhost.localdomain', timestamp: '2022-12-16T02:54:12.431395Z' },
    },
  },
  tokens: {
    'test-token': {
      appId: 'test-token',
      role: 'MEMBER',
      creation: {
        user: 'lb56789@localhost.localdomain',
        timestamp: '2022-12-02T08:28:05.364140Z',
      },
    },
  },
  creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-23T03:13:50.128853Z' },
};

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  switch (req.method) {
    case 'GET':
      res.status(200).json(projectMetadata);
      break;
    default:
      res.setHeader('Allow', ['GET', 'POST']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
