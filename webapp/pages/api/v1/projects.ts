import type { NextApiRequest, NextApiResponse } from 'next';

const projects = [
  {
    name: 'abcd',
    creator: { name: 'System', email: 'system@localhost.localdomain' },
    url: '/api/v1/projects/abcd',
    createdAt: '2022-11-02T04:16:12.444Z',
  },
  {
    name: 'xyz',
    creator: { name: 'System', email: 'system@localhost.localdomain' },
    url: '/api/v1/projects/xyz',
    createdAt: '2022-11-02T04:16:03.175Z',
  },
  {
    name: 'test',
    creator: { name: 'System', email: 'system@localhost.localdomain' },
    url: '/api/v1/projects/test',
    createdAt: '2022-11-01T06:51:50.553Z',
  },
];

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  switch (req.method) {
    case 'GET':
      res.status(200).json(projects);
      break;
    case 'POST':
      const { project } = req.body;
      projects.push(project);
      res.status(200).json(projects);
      break;
    default:
      res.setHeader('Allow', ['GET', 'POST']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
