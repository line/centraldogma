import { fireEvent, screen, waitFor } from '@testing-library/react';
import { NewProject } from 'dogma/features/project/NewProject';
import { renderWithProviders } from 'dogma/util/test-utils';
import { useAddNewProjectMutation, useGetMetadataPropertiesQuery } from 'dogma/features/api/apiSlice';

jest.mock('next/router', () => ({
  ...jest.requireActual('next/router'),
  push: jest.fn(),
}));

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  useAddNewProjectMutation: jest.fn(),
  useGetMetadataPropertiesQuery: jest.fn(),
}));

describe('NewProject', () => {
  let mockAddNewProject: jest.Mock;

  beforeEach(() => {
    mockAddNewProject = jest.fn().mockResolvedValue({ data: {} });
    (useAddNewProjectMutation as jest.Mock).mockReturnValue([mockAddNewProject, { isLoading: false }]);
    (useGetMetadataPropertiesQuery as jest.Mock).mockReturnValue({ data: {} });
  });

  it('creates a project without properties when nothing is declared', async () => {
    renderWithProviders(<NewProject />);
    fireEvent.change(screen.getByPlaceholderText('my-project-name'), { target: { value: 'foo' } });
    fireEvent.click(screen.getByText('Create'));
    await waitFor(() => expect(mockAddNewProject).toHaveBeenCalledWith({ name: 'foo', properties: undefined }));
  });

  it('renders the declared properties and sends them on submit', async () => {
    (useGetMetadataPropertiesQuery as jest.Mock).mockReturnValue({
      data: {
        project: {
          properties: { serviceId: { type: 'string' } },
          required: ['serviceId'],
        },
      },
    });
    renderWithProviders(<NewProject />);

    fireEvent.change(screen.getByPlaceholderText('my-project-name'), { target: { value: 'foo' } });
    fireEvent.change(screen.getByPlaceholderText('serviceId'), { target: { value: 'payment-service' } });
    fireEvent.click(screen.getByText('Create'));

    await waitFor(() =>
      expect(mockAddNewProject).toHaveBeenCalledWith({
        name: 'foo',
        properties: { serviceId: 'payment-service' },
      }),
    );
  });

  it('does not submit when a required property is missing', async () => {
    (useGetMetadataPropertiesQuery as jest.Mock).mockReturnValue({
      data: {
        project: {
          properties: { serviceId: { type: 'string' } },
          required: ['serviceId'],
        },
      },
    });
    renderWithProviders(<NewProject />);

    fireEvent.change(screen.getByPlaceholderText('my-project-name'), { target: { value: 'foo' } });
    fireEvent.click(screen.getByText('Create'));

    // The empty required property blocks the native form validation.
    await waitFor(() => expect(screen.getByLabelText(/serviceId/)).toBeInvalid());
    expect(mockAddNewProject).not.toHaveBeenCalled();
  });
});
