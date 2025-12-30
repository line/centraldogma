import { NextApiRequest, NextApiResponse } from 'next';
// eslint-disable-next-line import/no-extraneous-dependencies
import { faker } from '@faker-js/faker';
import { Token } from 'dogma/features/app-identity/AppIdentity';

const newToken = (id: number) => {
  const token: Token = {
    appId: `${faker.animal.snake().replaceAll(' ', '-').toLowerCase()}-${id}`,
    type: 'TOKEN',
    secret: faker.datatype.uuid(),
    systemAdmin: faker.datatype.boolean(),
    allowGuestAccess: true,
    creation: { user: faker.internet.email(), timestamp: faker.datatype.datetime().toISOString() },
  };
  if (id & 1) {
    token.deactivation = { user: faker.internet.email(), timestamp: faker.datatype.datetime().toISOString() };
  }
  return token;
};

const tokenList: Token[] = [];

const makeData = (len: number) => {
  for (let i = len; i > 0; i--) {
    tokenList.push(newToken(i));
  }
};
makeData(500);

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const { token } = req.body;
  switch (req.method) {
    case 'GET':
      res.status(200).json(tokenList);
      break;
    case 'POST':
      tokenList.push(token);
      res.status(200).json(tokenList);
      break;
    default:
      res.setHeader('Allow', ['GET', 'POST']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
