import { NextApiRequest, NextApiResponse } from 'next';

const historyList = [
  {
    revision: { major: 8, minor: 0, revisionNumber: '8' },
    author: { name: 'ldap123456', email: 'ldap123456@localhost.localdomain' },
    timestamp: '2022-11-30T11:48:57Z',
    summary: 'Edit /zzzzz',
    detail: { content: '', markup: 'PLAINTEXT' },
    diffs: [],
  },
  {
    revision: { major: 7, minor: 0, revisionNumber: '7' },
    author: { name: 'ldap123456', email: 'ldap123456@localhost.localdomain' },
    timestamp: '2022-11-30T10:57:41Z',
    summary: 'Add /test',
    detail: { content: 'zzz', markup: 'PLAINTEXT' },
    diffs: [],
  },
  {
    revision: { major: 6, minor: 0, revisionNumber: '6' },
    author: { name: 'ldap123456', email: 'ldap123456@localhost.localdomain' },
    timestamp: '2022-11-24T10:56:10Z',
    summary: 'Add /yyyyyyyy',
    detail: { content: '', markup: 'PLAINTEXT' },
    diffs: [],
  },
  {
    revision: { major: 5, minor: 0, revisionNumber: '5' },
    author: { name: 'ldap123456', email: 'ldap123456@localhost.localdomain' },
    timestamp: '2022-11-24T10:53:51Z',
    summary: 'Add /uuuuu',
    detail: { content: '', markup: 'PLAINTEXT' },
    diffs: [],
  },
  {
    revision: { major: 4, minor: 0, revisionNumber: '4' },
    author: { name: 'ldap123456', email: 'ldap123456@localhost.localdomain' },
    timestamp: '2022-11-24T10:53:39Z',
    summary: 'Add /zzzzz',
    detail: { content: '', markup: 'PLAINTEXT' },
    diffs: [],
  },
  {
    revision: { major: 3, minor: 0, revisionNumber: '3' },
    author: { name: 'ldap123456', email: 'ldap123456@localhost.localdomain' },
    timestamp: '2022-11-24T10:53:26Z',
    summary: 'Add /123456',
    detail: { content: '', markup: 'PLAINTEXT' },
    diffs: [],
  },
  {
    revision: { major: 2, minor: 0, revisionNumber: '2' },
    author: { name: 'ldap123456', email: 'ldap123456@localhost.localdomain' },
    timestamp: '2022-11-24T10:52:55Z',
    summary: 'Add /abc',
    detail: { content: '', markup: 'PLAINTEXT' },
    diffs: [],
  },
  {
    revision: { major: 1, minor: 0, revisionNumber: '1' },
    author: { name: 'ldap123456', email: 'ldap123456@localhost.localdomain' },
    timestamp: '2022-11-23T03:16:17Z',
    summary: 'Create a new repository',
    detail: { content: '', markup: 'PLAINTEXT' },
    diffs: [],
  },
];

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const { historyItem } = req.body;
  switch (req.method) {
    case 'GET':
      res.status(200).json(historyList);
      break;
    case 'POST':
      historyList.push(historyItem);
      res.status(200).json(historyList);
      break;
    default:
      res.setHeader('Allow', ['GET', 'POST']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
