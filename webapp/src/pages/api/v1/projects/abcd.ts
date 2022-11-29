import type { NextApiRequest, NextApiResponse } from 'next';

export const mockRepos = [
  {
    name: 'meta',
    creator: { name: 'System', email: 'system@localhost.localdomain' },
    headRevision: 1,
    url: '/api/v1/projects/abcd/repos/meta',
    createdAt: '2022-11-23T03:13:49.581Z',
  },
  {
    name: 'repo1',
    creator: { name: 'dummy', email: 'dummy@localhost.localdomain' },
    headRevision: 6,
    url: '/api/v1/projects/abcd/repos/repo1',
    createdAt: '2022-11-23T03:16:17.880Z',
  },
  {
    name: 'repo2',
    creator: { name: 'dummy', email: 'dummy@localhost.localdomain' },
    headRevision: 1,
    url: '/api/v1/projects/abcd/repos/repo2',
    createdAt: '2022-11-28T03:01:47.262Z',
  },
];

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  switch (req.method) {
    case 'GET':
      res.status(200).json(mockRepos);
      break;
    case 'POST':
      const { repo } = req.body;
      mockRepos.push(repo);
      res.status(200).json(mockRepos);
      break;
    default:
      res.setHeader('Allow', ['GET', 'POST']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
