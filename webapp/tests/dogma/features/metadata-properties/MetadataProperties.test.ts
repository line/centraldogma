import {
  hasDeclaredProperties,
  toMetadataProperties,
  validateJsonObject,
} from 'dogma/features/metadata-properties/MetadataProperties';

describe('hasDeclaredProperties', () => {
  it('returns true only when the schema declares top-level properties', () => {
    expect(hasDeclaredProperties(undefined)).toBe(false);
    expect(hasDeclaredProperties({})).toBe(false);
    expect(hasDeclaredProperties({ properties: {} })).toBe(false);
    expect(hasDeclaredProperties({ properties: { serviceId: { type: 'string' } } })).toBe(true);
  });
});

describe('validateJsonObject', () => {
  it('accepts an empty value and a JSON object', () => {
    expect(validateJsonObject(undefined)).toBe(true);
    expect(validateJsonObject('')).toBe(true);
    expect(validateJsonObject('{ "a": 1 }')).toBe(true);
  });

  it('rejects invalid JSON and non-object values', () => {
    expect(validateJsonObject('not-json')).toBe('Invalid JSON');
    expect(validateJsonObject('[1]')).toBe('Must be a JSON object');
    expect(validateJsonObject('null')).toBe('Must be a JSON object');
    expect(validateJsonObject('42')).toBe('Must be a JSON object');
  });
});

describe('toMetadataProperties', () => {
  const schema = {
    properties: {
      serviceId: { type: 'string' },
      replicas: { type: 'integer' },
      canary: { type: 'boolean' },
    },
    required: ['serviceId'],
  };

  it('returns undefined without a schema or without values', () => {
    expect(toMetadataProperties(undefined, { properties: { serviceId: 'foo' } })).toBeUndefined();
    expect(toMetadataProperties(schema, {})).toBeUndefined();
    expect(toMetadataProperties(schema, { properties: { serviceId: '' } })).toBeUndefined();
  });

  it('converts values to the declared types', () => {
    expect(
      toMetadataProperties(schema, {
        properties: { serviceId: 'foo', replicas: '3', canary: true },
      }),
    ).toEqual({ serviceId: 'foo', replicas: 3, canary: true });
  });

  it('ignores values that are not declared in the schema', () => {
    expect(
      toMetadataProperties(schema, {
        properties: { serviceId: 'foo', undeclared: 'x' },
      }),
    ).toEqual({ serviceId: 'foo' });
  });

  it('parses the raw JSON field when the schema has no top-level properties', () => {
    const refSchema = { required: ['serviceId'] };
    expect(toMetadataProperties(refSchema, { propertiesJson: '{ "serviceId": "foo", "extra": 1 }' })).toEqual({
      serviceId: 'foo',
      extra: 1,
    });
    expect(toMetadataProperties(refSchema, { propertiesJson: '' })).toBeUndefined();
    expect(toMetadataProperties(refSchema, {})).toBeUndefined();
  });
});
