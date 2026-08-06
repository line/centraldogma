import {
  Checkbox,
  Code,
  FormControl,
  FormErrorMessage,
  FormHelperText,
  FormLabel,
  Input,
  Select,
  Textarea,
  useColorModeValue,
} from '@chakra-ui/react';
import { FieldError, useFormContext } from 'react-hook-form';
import {
  hasDeclaredProperties,
  MetadataPropertiesFormData,
  MetadataPropertiesSchema,
  MetadataPropertySchema,
  validateJsonObject,
} from 'dogma/features/metadata-properties/MetadataProperties';

const PATTERN_MISMATCH = 'PATTERN_MISMATCH';

// Renders input fields for the metadata properties declared in the JSON Schema of a resource type.
// Must be rendered inside a react-hook-form `FormProvider`.
export const MetadataPropertiesFields = ({ schema }: { schema: MetadataPropertiesSchema }) => {
  const {
    register,
    formState: { errors },
  } = useFormContext<MetadataPropertiesFormData>();

  if (!hasDeclaredProperties(schema)) {
    // The schema declares its shape without a top-level `properties` keyword; fall back to raw JSON.
    const error = errors.propertiesJson;
    return (
      <FormControl mt={4} isInvalid={!!error}>
        <FormLabel>Properties (JSON)</FormLabel>
        <Textarea
          placeholder='{ "key": "value" }'
          {...register('propertiesJson', { validate: validateJsonObject })}
        />
        {!error && <FormHelperText pl={1}>Additional properties required by this server.</FormHelperText>}
        {error && <FormErrorMessage>{error.message}</FormErrorMessage>}
      </FormControl>
    );
  }

  const required = schema.required || [];
  return (
    <>
      {Object.entries(schema.properties).map(([name, property]) => (
        <PropertyField key={name} name={name} property={property} isRequired={required.includes(name)} />
      ))}
    </>
  );
};

const PropertyField = ({
  name,
  property,
  isRequired,
}: {
  name: string;
  property: MetadataPropertySchema;
  isRequired: boolean;
}) => {
  const {
    register,
    watch,
    formState: { errors },
  } = useFormContext<MetadataPropertiesFormData>();
  const emptySelectColor = useColorModeValue('gray.500', 'whiteAlpha.500');
  const error = errors.properties?.[name] as FieldError | undefined;
  const fieldName = `properties.${name}` as const;
  const label = property.title || name;
  const selectedValue = watch(fieldName);

  if (property.type === 'boolean') {
    return (
      <FormControl mt={4}>
        <Checkbox colorScheme="teal" {...register(fieldName)}>
          {label}
        </Checkbox>
        {property.description && <FormHelperText pl={1}>{property.description}</FormHelperText>}
      </FormControl>
    );
  }

  const validate = (value: unknown): string | true => {
    if (value === undefined || value === '') {
      return isRequired ? `${label} is required` : true;
    }
    if (property.pattern && !new RegExp(property.pattern).test(String(value))) {
      return PATTERN_MISMATCH;
    }
    return true;
  };

  let input;
  if (property.enum) {
    input = (
      <Select
        placeholder={`Select ${label}`}
        color={selectedValue ? 'inherit' : emptySelectColor}
        {...register(fieldName, { validate })}
      >
        {property.enum.map((value) => (
          <option key={String(value)} value={String(value)}>
            {String(value)}
          </option>
        ))}
      </Select>
    );
  } else {
    input = (
      <Input
        type={property.type === 'integer' || property.type === 'number' ? 'number' : 'text'}
        placeholder={property.examples?.length ? String(property.examples[0]) : label}
        {...register(fieldName, { validate })}
      />
    );
  }
  return (
    <FormControl mt={4} isInvalid={!!error} isRequired={isRequired}>
      <FormLabel>{label}</FormLabel>
      {input}
      {property.description && !error && <FormHelperText pl={1}>{property.description}</FormHelperText>}
      {error && (
        <FormErrorMessage>
          {error.message === PATTERN_MISMATCH ? (
            <>
              Must match the pattern: <Code ml={1}>{property.pattern}</Code>
            </>
          ) : (
            error.message
          )}
        </FormErrorMessage>
      )}
    </FormControl>
  );
};
