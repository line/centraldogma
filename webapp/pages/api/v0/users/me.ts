import type { NextApiRequest, NextApiResponse } from 'next';

const me = {
  login: 'foo',
  name: 'foo',
  email: 'foo@localhost.localdomain',
  roles: ['LEVEL_USER'],
  admin: false,
};

export default function handler(_req: NextApiRequest, res: NextApiResponse) {
  res.status(200).json(me);
}
