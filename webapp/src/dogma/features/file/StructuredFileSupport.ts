import { isJson, isJson5, isYaml } from 'dogma/util/path-util';
import JSON5 from 'json5';
import YAML from 'yaml';

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
  return ['json', 'json5', 'yaml', 'yml'].includes(language);
}
