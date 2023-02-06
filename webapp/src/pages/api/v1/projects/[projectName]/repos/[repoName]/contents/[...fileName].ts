import type { NextApiRequest, NextApiResponse } from 'next';

const mockFile = {
  revision: '5',
  path: '/file1',
  type: 'JSON',
  content:
    '[{"name":"orange","repos":[],"members":[{"login":"User2","additionUser":"User","additionTime":"2017-11-13T17:23:12.068+09:00[Asia/Seoul]"}],"tokens":[{"appId":"cook","secret":"y","additionUser":"User","additionTime":"2017-11-13T17:23:12.206+09:00[Asia/Seoul]"},{"appId":"ive","secret":"z","additionUser":"User","additionTime":"2017-11-13T17:23:12.227+09:00[Asia/Seoul]"},{"appId":"jobs","secret":"x","additionUser":"User","additionTime":"2017-11-13T17:23:12.186+09:00[Asia/Seoul]"}],"additionUser":"User","additionTime":"2017-11-13T17:23:11.883+09:00[Asia/Seoul]","removed":true},{"name":"apple","repos":[{"name":"coffee","additionUser":"User","additionTime":"2017-11-13T17:23:12.163+09:00[Asia/Seoul]"},{"name":"hamburger","additionUser":"User","additionTime":"2017-11-13T17:23:12.139+09:00[Asia/Seoul]"}],"members":[],"tokens":[],"additionUser":"User","additionTime":"2017-11-13T17:23:11.706+09:00[Asia/Seoul]","removed":false}]',
  name: 'file1',
};

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  switch (req.method) {
    case 'GET':
      res.status(200).json(mockFile);
      break;
    default:
      res.setHeader('Allow', ['GET']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
