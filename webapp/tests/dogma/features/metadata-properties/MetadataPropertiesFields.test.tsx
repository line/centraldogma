import { render, screen } from '@testing-library/react';
import { FormProvider, useForm } from 'react-hook-form';
import { MetadataPropertiesFields } from 'dogma/features/metadata-properties/MetadataPropertiesFields';
import { MetadataPropertiesSchema } from 'dogma/features/metadata-properties/MetadataProperties';

const Harness = ({ schema }: { schema: MetadataPropertiesSchema }) => {
  const methods = useForm();
  return (
    <FormProvider {...methods}>
      <MetadataPropertiesFields schema={schema} />
    </FormProvider>
  );
};

describe('MetadataPropertiesFields', () => {
  it('renders an input per declared property', () => {
    render(
      <Harness
        schema={{
          properties: {
            serviceId: { type: 'string', description: 'The ID of the owning service.' },
            replicas: { type: 'integer' },
          },
          required: ['serviceId'],
        }}
      />,
    );
    expect(screen.getByLabelText(/serviceId/)).toBeInTheDocument();
    expect(screen.getByText('The ID of the owning service.')).toBeInTheDocument();
    expect(screen.getByLabelText(/replicas/)).toHaveAttribute('type', 'number');
  });

  it('renders a select for an enum property', () => {
    render(
      <Harness
        schema={{
          properties: { env: { type: 'string', enum: ['dev', 'prod'] } },
        }}
      />,
    );
    const select = screen.getByLabelText(/env/);
    expect(select.tagName).toBe('SELECT');
    expect(screen.getByRole('option', { name: 'dev' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'prod' })).toBeInTheDocument();
  });

  it('renders a checkbox for a boolean property', () => {
    render(<Harness schema={{ properties: { canary: { type: 'boolean' } } }} />);
    expect(screen.getByRole('checkbox', { name: 'canary' })).toBeInTheDocument();
  });

  it('falls back to a raw JSON field when the schema has no top-level properties', () => {
    render(<Harness schema={{ required: ['serviceId'] }} />);
    expect(screen.getByLabelText(/Properties \(JSON\)/)).toBeInTheDocument();
  });
});
