import { NextApiRequest, NextApiResponse } from 'next';
import { TOTAL_REVISION } from 'pages/api/v1/projects/[projectName]/repos/[repoName]/commits/[revision]';

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const {
    query: { revision },
    method,
  } = req;
  switch (method) {
    case 'GET':
      res.status(200).json({ revision: TOTAL_REVISION + parseInt(revision as string) + 1 });
      break;
    default:
      res.setHeader('Allow', ['GET']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
