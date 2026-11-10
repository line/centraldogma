/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
import { isJson, isJson5, isYaml } from 'dogma/util/path-util';
import JSON5 from 'json5';
import YAML from 'yaml';

// Returns the upsert change type for the file name without validating its content.
export function guessChangeType(fileName: string): string {
  if (isJson(fileName) || isJson5(fileName)) {
    return 'UPSERT_JSON';
  } else if (isYaml(fileName)) {
    return 'UPSERT_YAML';
  } else {
    return 'UPSERT_TEXT';
  }
}

export function detectChangeType(fileName: string, content: string): string {
  if (isJson(fileName)) {
    // Parse content to validate JSON format
    JSON.parse(content);
    return 'UPSERT_JSON';
  } else if (isJson5(fileName)) {
    JSON5.parse(content);
    return 'UPSERT_JSON';
  } else if (isYaml(fileName)) {
    YAML.parse(content);
    return 'UPSERT_YAML';
  } else {
    return 'UPSERT_TEXT';
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function parseContent(language: string, content: string): any {
  switch (language) {
    case 'json':
      return JSON.parse(content);
    case 'json5':
      return JSON5.parse(content);
    case 'yaml':
      return YAML.parse(content);
    default:
      throw new Error(`Unsupported structured file format: ${language}`);
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function stringifyContent(language: string, data: any): string {
  switch (language) {
    case 'json':
      return JSON.stringify(data, null, 2);
    case 'json5':
      return JSON5.stringify(data, null, 2);
    case 'yaml':
      return YAML.stringify(data);
    default:
      throw new Error(`Unsupported structured file format: ${language}`);
  }
}

export function isStructuredFile(language: string): boolean {
  return ['json', 'json5', 'yaml'].includes(language);
}
