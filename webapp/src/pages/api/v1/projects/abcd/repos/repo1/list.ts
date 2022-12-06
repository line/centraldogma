import { NextApiRequest, NextApiResponse } from 'next';

const fileList = [
  {
    revision: 6,
    path: '/123456',
    type: 'TEXT',
    url: '/api/v1/projects/Gamma/repos/repo1/contents/123456',
  },
  { revision: 6, path: '/abc', type: 'TEXT', url: '/api/v1/projects/Gamma/repos/repo1/contents/abc' },
  {
    revision: 6,
    path: '/uuuuu',
    type: 'TEXT',
    url: '/api/v1/projects/Gamma/repos/repo1/contents/uuuuu',
  },
  {
    revision: 6,
    path: '/yyyyyyyy',
    type: 'TEXT',
    url: '/api/v1/projects/Gamma/repos/repo1/contents/yyyyyyyy',
  },
  {
    revision: 6,
    path: '/zzzzz',
    type: 'TEXT',
    url: '/api/v1/projects/Gamma/repos/repo1/contents/zzzzz',
  },
];

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const { fileItem } = req.body;
  switch (req.method) {
    case 'GET':
      res.status(200).json(fileList);
      break;
    case 'POST':
      fileList.push(fileItem);
      res.status(200).json(fileList);
      break;
    default:
      res.setHeader('Allow', ['GET', 'POST']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
