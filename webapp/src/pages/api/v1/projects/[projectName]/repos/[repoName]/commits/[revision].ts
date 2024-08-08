import { NextApiRequest, NextApiResponse } from 'next';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
// eslint-disable-next-line import/no-extraneous-dependencies
import { faker } from '@faker-js/faker';

export const TOTAL_REVISION = 1000;

const newHistory = (i: number): HistoryDto => {
  return {
    revision: i,
    author: { name: faker.internet.userName(), email: faker.internet.email() },
    commitMessage: { summary: faker.lorem.sentence(), detail: faker.lorem.paragraph(), markup: 'PLAINTEXT' },
    pushedAt: faker.datatype.datetime().toISOString(),
  };
};

const historyList: HistoryDto[] = [];
const makeData = (len: number) => {
  for (let i = len; i > 0; i--) {
    historyList.push(newHistory(i));
  }
};

makeData(TOTAL_REVISION);

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const {
    query: { revision, to },
    method,
  } = req;
  switch (method) {
    case 'GET':
      res.status(200).json(historyList.slice(-parseInt(revision as string) - 1, -parseInt(to as string)));
      break;
    default:
      res.setHeader('Allow', ['GET']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
