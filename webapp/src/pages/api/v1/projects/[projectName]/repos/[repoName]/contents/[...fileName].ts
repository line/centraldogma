import type { NextApiRequest, NextApiResponse } from 'next';
import { FileDto } from 'dogma/features/file/FileDto';
import { faker } from '@faker-js/faker';

function text(path: string): FileDto {
  return {
    revision: 5,
    url: `/api/v1/projects/project1/repos/repo1/contents/${path}`,
    path: path,
    type: 'TEXT',
    content: 'This is a text file',
  };
}

function json(path: string): FileDto {
  return {
    revision: 5,
    url: `/api/v1/projects/project1/repos/repo1/contents/${path}`,
    path: path,
    type: 'JSON',
    content: {
      employee: {
        name: `${faker.name.fullName()}`,
        salary: faker.random.numeric(),
        married: true,
      },
    },
  };
}

function yml(path: string): FileDto {
  return {
    revision: 5,
    url: `/api/v1/projects/project1/repos/repo1/contents/${path}`,
    type: 'TEXT',
    path: path,
    content: `
---
 doe: "a deer, a female deer"
 ray: "a drop of golden sun"
 pi: 3.14159
 xmas: true
 french-hens: ${faker.random.numeric()}
 calling-birds:
   - ${faker.name.lastName()}
   - dewey
   - louie
   - fred
 xmas-fifth-day:
   calling-birds: four
   french-hens: 3
   golden-rings: 5
   partridges:
     count: 1
     location: "a pear tree"
   turtle-doves: two
`,
  };
}

function javascript(path: string): FileDto {
  return {
    revision: 5,
    url: `/api/v1/projects/project1/repos/repo1/contents/${path}`,
    type: 'TEXT',
    path: path,
    content: `
 for (var i = 1; i < ${faker.random.numeric()}; i++) {
    if (i % 15 == 0) console.log("FizzBuzz");
    else if (i % 3 == 0) console.log("Fizz");
    else if (i % 5 == 0) console.log("Buzz");
    else console.log(i);
}
`,
  };
}

function toml(path: string): FileDto {
  return {
    revision: 5,
    url: `/api/v1/projects/project1/repos/repo1/contents/${path}`,
    type: 'TEXT',
    path: path,
    content: `
# This is a TOML document

title = "TOML Example"

[owner]
name = "${faker.name.fullName()}"
dob = 1979-05-27T07:32:00-08:00

[database]
enabled = ${faker.helpers.arrayElement([true, false])}
ports = [ 8000, 8001, 8002 ]
data = [ ["delta", "phi"], [3.14] ]
temp_targets = { cpu = 79.5, case = 72.0 }

[servers]

[servers.alpha]
ip = "10.0.0.1"
role = "frontend"

[servers.beta]
ip = "10.0.0.2"
role = "backend"
`,
  };
}

function getFile(path: string): FileDto {
  const extension = path.split('.').pop();
  switch (extension) {
    case 'json':
      return json(path);
    case 'yml':
      return yml(path);
    case 'js':
      return javascript(path);
    case 'toml':
      return toml(path);
    default:
      return text(path);
  }
}

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  switch (req.method) {
    case 'GET':
      const url = new URL(`http://localhost${req.url}`);
      const path = '/' + url.pathname.split('/').pop();
      if (path.endsWith('**')) {
        res
          .status(200)
          .json([
            getFile('/file1.json'),
            getFile('/file2.yml'),
            getFile('/file3.js'),
            getFile('/file4.toml'),
            getFile('/file5.txt'),
          ]);
      } else {
        res.status(200).json(getFile(path));
      }
      break;
    default:
      res.setHeader('Allow', ['GET']);
      res.status(405).end(`Method ${req.method} Not Allowed`);
      break;
  }
}
