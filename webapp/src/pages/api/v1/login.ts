import type { NextApiRequest, NextApiResponse } from 'next';

const login = { token_type: 'Bearer', access_token: 'xxxxxxxxxxxxxx', expires_in: 500000, refresh_token: '' };

export default function handler(_req: NextApiRequest, res: NextApiResponse) {
  res.status(200).json(login);
}
