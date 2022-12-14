import { NextApiRequest, NextApiResponse } from 'next';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
// eslint-disable-next-line import/no-extraneous-dependencies
import { faker } from '@faker-js/faker';

const newHistory = (i: number): HistoryDto => {
  return {
    revision: {
      major: faker.datatype.number({ min: 0, max: 10 }),
      minor: faker.datatype.number({ min: 0, max: 10 }),
      revisionNumber: i.toString(),
    },
    author: {
      name: faker.internet.userName(),
      email: faker.internet.email(),
    },
    timestamp: '2022-11-23T03:13:49.581Z',
    summary: faker.lorem.sentence(),
    detail: {
      content: faker.lorem.paragraph(),
      markup: 'PLAINTEXT',
    },
    diffs: [],
  };
};

const historyList: HistoryDto[] = [];
const makeData = (len: number) => {
  for (let i = len; i > 0; i--) {
    historyList.push(newHistory(i));
  }
};
makeData(10);

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
