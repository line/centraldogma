import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import apiLinkName from '../components/api-link-name.js';
import { createRemarkApiLink } from './remark-api-link-transform.mjs';

const { simpleApiName } = apiLinkName;

const generatedIndex = JSON.parse(
  readFileSync(new URL('../../gen-src/api-index.json', import.meta.url)),
);

const linkNode = (value, url = 'type') => ({
  type: 'root',
  children: [
    {
      type: 'paragraph',
      children: [{ type: 'link', url, children: [{ type: 'text', value }] }],
    },
  ],
});

test('transforms a fully qualified API link', () => {
  const href = 'https://javadoc.example/CentralDogma.html';
  const tree = linkNode('com.linecorp.centraldogma.client.CentralDogma');
  createRemarkApiLink({
    'com.linecorp.centraldogma.client.CentralDogma': href,
  })()(tree);

  const link = tree.children[0].children[0];
  assert.equal(link.url, `type://${href}`);
  assert.equal(
    link.children[0].value,
    'com.linecorp.centraldogma.client.CentralDogma',
  );
});

test('preserves plural, annotation, and display options', () => {
  const href = 'https://javadoc.example/RequestTimeout.html';
  const tree = linkNode('@RequestTimeout?full', 'typeplural');
  createRemarkApiLink({ RequestTimeout: href })()(tree);

  const link = tree.children[0].children[0];
  assert.equal(link.url, `typeplural://${href}?full`);
  assert.equal(link.children[0].value, '@RequestTimeout');
});

test('fails when an API link is missing or ambiguous', () => {
  const tree = linkNode('CentralDogma');
  assert.throws(
    () => createRemarkApiLink({})()(tree),
    /Cannot find a unique Javadoc target.*fully qualified name/,
  );
});

test('generates normalized method keys and omits ambiguous aliases', () => {
  const centralDogma = 'com.linecorp.centraldogma.client.CentralDogma';
  assert.match(generatedIndex[`${centralDogma}#listProjects()`], /#listProjects\(\)$/);
  assert.match(
    generatedIndex[`${centralDogma}#createProject(String)`],
    /#createProject\(java\.lang\.String\)$/,
  );
  assert.match(
    generatedIndex[`${centralDogma}#createProject(java.lang.String)`],
    /#createProject\(java\.lang\.String\)$/,
  );
  assert.match(
    generatedIndex[`${centralDogma}#createRepository(String,String)`],
    /#createRepository\(java\.lang\.String,java\.lang\.String\)$/,
  );
  assert.match(
    generatedIndex[
      `${centralDogma}#mergeFiles(String,String,Revision,MergeSource...)`
    ],
    /#mergeFiles\(.*MergeSource\.\.\.\)$/,
  );
  assert.equal(generatedIndex.CentralDogma, undefined);
});

test('shortens fully qualified owners and method parameters', () => {
  assert.equal(
    simpleApiName(
      'com.linecorp.centraldogma.client.CentralDogma#createProject(java.lang.String)',
    ),
    'CentralDogma#createProject(String)',
  );
  assert.equal(
    simpleApiName('@com.linecorp.centraldogma.server.annotation.Nullable'),
    '@Nullable',
  );
  assert.equal(
    simpleApiName(
      'com.linecorp.centraldogma.client.CentralDogma#mergeFiles(' +
        'java.lang.String,com.linecorp.centraldogma.common.MergeSource...)',
    ),
    'CentralDogma#mergeFiles(String,MergeSource...)',
  );
});
