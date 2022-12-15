import type { NextApiRequest, NextApiResponse } from 'next';
import { RepoDto } from 'dogma/features/repo/RepoDto';
// eslint-disable-next-line import/no-extraneous-dependencies
import { faker } from '@faker-js/faker';

const newRepo = (id: number): RepoDto => {
  return {
    name: `${id}-${faker.animal.dog().replaceAll(' ', '-').toLowerCase()}`,
    creator: {
      name: faker.name.firstName(),
      email: faker.internet.email(),
    },
    headRevision: parseInt(faker.random.numeric()),
    url: faker.internet.url(),
    createdAt: faker.datatype.datetime().toISOString(),
  };
};

const mockRepos = [
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
];

const makeData = (len: number) => {
  for (let i = 0; i < len; i++) {
    mockRepos.push(newRepo(i));
  }
};

makeData(20);

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const { repo } = req.body;
  switch (req.method) {
    case 'GET':
      res.status(200).json(mockRepos);
      break;
    case 'POST':
      mockRepos.push(repo);
      res.status(200).json(mockRepos);
      break;
    default:
      res.setHeader('Allow', ['GET', 'POST']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
