import { isJson, isJson5, isYaml } from 'dogma/util/path-util';
import JSON5 from 'json5';
import YAML from 'yaml';

export function validateFileContent(fileName: string, content: string): string {
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
