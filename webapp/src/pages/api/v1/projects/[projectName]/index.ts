import type { NextApiRequest, NextApiResponse } from 'next';

const repoDetail = {
  name: 'abcd',
  repos: {
    meta: {
      name: 'meta',
      perRolePermissions: { owner: ['READ', 'WRITE'], member: [] as string[], guest: [] as string[] },
      perUserPermissions: {},
      perTokenPermissions: {},
      creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-23T03:13:50.129903Z' },
    },
    repo1: {
      name: 'repo1',
      perRolePermissions: { owner: ['READ', 'WRITE'], member: ['READ', 'WRITE'], guest: [] as string[] },
      perUserPermissions: {},
      perTokenPermissions: {},
      creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-23T03:16:18.853509Z' },
    },
    repo2: {
      name: 'repo2',
      perRolePermissions: { owner: ['READ', 'WRITE'], member: ['READ', 'WRITE'], guest: [] as string[] },
      perUserPermissions: {},
      perTokenPermissions: {},
      creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-28T03:01:48.202144Z' },
      removal: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-29T04:16:12.870155Z' },
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
      creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-23T03:13:50.129903Z' },
    },
  },
  tokens: {},
  creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-23T03:13:50.129903Z' },
};

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  switch (req.method) {
    case 'GET':
      res.status(200).json(repoDetail);
      break;
    default:
      res.setHeader('Allow', ['GET', 'POST']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
