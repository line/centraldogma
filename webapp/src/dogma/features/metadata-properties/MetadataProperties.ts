export interface MetadataPropertySchema {
  type?: string;
  pattern?: string;
  enum?: (string | number)[];
  title?: string;
  description?: string;
  examples?: unknown[];
}

export interface MetadataPropertiesSchema {
  properties?: Record<string, MetadataPropertySchema>;
  required?: string[];
}

// The response of `GET /api/v1/metadataProperties`. Each field is the JSON Schema that the
// `properties` of the corresponding resource must conform to at creation time.
export interface MetadataProperties {
  project?: MetadataPropertiesSchema;
  repo?: MetadataPropertiesSchema;
  appIdentity?: MetadataPropertiesSchema;
}

// The form fields managed by `MetadataPropertiesFields`.
export type MetadataPropertiesFormData = {
  properties?: Record<string, string | boolean>;
  propertiesJson?: string;
};

export function hasDeclaredProperties(schema: MetadataPropertiesSchema | undefined): boolean {
  return !!schema && !!schema.properties && Object.keys(schema.properties).length > 0;
}

export function validateJsonObject(value?: string): string | true {
  if (!value || value.trim() === '') {
    return true;
  }
  try {
    const parsed = JSON.parse(value);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return 'Must be a JSON object';
    }
    return true;
  } catch {
    return 'Invalid JSON';
  }
}

// Builds the `properties` object of a creation request from the form values, converting each value
// to the type declared in the schema. Returns undefined if there is nothing to send.
export function toMetadataProperties(
  schema: MetadataPropertiesSchema | undefined,
  formData: MetadataPropertiesFormData,
): Record<string, unknown> | undefined {
  if (!schema) {
    return undefined;
  }
  if (!hasDeclaredProperties(schema)) {
    const json = formData.propertiesJson?.trim();
    return json ? JSON.parse(json) : undefined;
  }
  const result: Record<string, unknown> = {};
  for (const [name, property] of Object.entries(schema.properties)) {
    const value = formData.properties?.[name];
    if (value === undefined || value === '') {
      continue;
    }
    switch (property.type) {
      case 'boolean':
        result[name] = value === true || value === 'true';
        break;
      case 'integer':
      case 'number':
        result[name] = Number(value);
        break;
      default:
        result[name] = value;
    }
  }
  return Object.keys(result).length > 0 ? result : undefined;
}
