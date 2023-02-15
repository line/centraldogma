import type { NextApiRequest, NextApiResponse } from 'next';
// eslint-disable-next-line import/no-extraneous-dependencies
import { faker } from '@faker-js/faker';
import { ProjectDto } from 'dogma/features/project/ProjectDto';

const newProject = (id: number): ProjectDto => {
  return {
    name: `${id}-${faker.animal.cat().replaceAll(' ', '-').toLowerCase()}`,
    creator: {
      name: faker.name.firstName(),
      email: faker.internet.email(),
    },
    url: faker.internet.url(),
    createdAt: faker.datatype.datetime().toString(),
  };
};

const projects: ProjectDto[] = [
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
];

const makeData = (len: number) => {
  for (let i = 0; i < len; i++) {
    projects.push(newProject(i));
  }
};

makeData(200);

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const { name } = req.body;
  switch (req.method) {
    case 'GET':
      res.status(200).json(projects);
      break;
    case 'POST':
      projects.push({
        name: name,
        creator: {
          name: faker.name.firstName(),
          email: faker.internet.email(),
        },
        url: faker.internet.url(),
        createdAt: faker.datatype.datetime().toString(),
      });
      res.status(200).json(projects);
      break;
    default:
      res.setHeader('Allow', ['GET', 'POST']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
