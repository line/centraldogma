import { NextApiRequest, NextApiResponse } from 'next';
import { FileDto } from 'dogma/features/file/FileDto';
// eslint-disable-next-line import/no-extraneous-dependencies
import { faker } from '@faker-js/faker';

const newFile = (id: number): FileDto => {
  return {
    revision: faker.datatype.number({
      min: 1,
      max: 10,
    }),
    path: `/${id}-${faker.animal.rabbit().replaceAll(' ', '-').toLowerCase()}`,
    type: faker.helpers.arrayElement(['TEXT', 'DIRECTORY', 'JSON', 'YML']),
    url: faker.internet.url(),
  };
};
const fileList: FileDto[] = [];
const makeData = (len: number) => {
  for (let i = 0; i < len; i++) {
    fileList.push(newFile(i));
  }
};
makeData(20);

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const { fileItem } = req.body;
  const { query } = req;
  const { revision } = query;
  const revisionNumber = parseInt(revision as string);
  const filtered = isNaN(revisionNumber)
    ? fileList
    : fileList.filter((file: FileDto) => file.revision <= revisionNumber);
  switch (req.method) {
    case 'GET':
      res.status(200).json(filtered);
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
