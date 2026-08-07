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
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import * as jsYaml from 'js-yaml';
import { renderWithProviders } from 'dogma/util/test-utils';
import { K8sAggregatorEditor } from 'dogma/features/xds/K8sAggregatorEditor';
import * as xdsApiSlice from 'dogma/features/xds/xdsApiSlice';

jest.mock('next/router', () => ({
  __esModule: true,
  default: { push: jest.fn() },
}));

jest.mock('dogma/features/xds/useGroupWriteAccess', () => ({
  useGroupWriteAccess: () => ({ hasWrite: true, isLoading: false }),
}));

// Stub out the status panel and preview modal — they make additional API calls unrelated to ID validation.
jest.mock('dogma/features/xds/K8sAggregatorStatus', () => ({
  K8sAggregatorStatus: () => null,
}));
jest.mock('dogma/features/xds/K8sAggregatorPreviewModal', () => ({
  K8sAggregatorPreviewModal: () => null,
}));

// chakra-react-select does not work in JSDOM; replace with a plain <select>.
jest.mock('chakra-react-select', () => ({
  Select: ({ name, options, onChange, value, placeholder }: any) => (
    <select
      name={name}
      value={value?.value || ''}
      onChange={(e) => {
        const selected = options?.find((o: any) => o.value === e.target.value);
        onChange(selected ?? null);
      }}
    >
      <option value="">{placeholder}</option>
      {options?.map((o: any) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  ),
}));

jest.mock('dogma/features/xds/xdsApiSlice', () => ({
  // Preserve reducerPath and reducer so the Redux store initialises correctly.
  ...jest.requireActual('dogma/features/xds/xdsApiSlice'),
  useCreateK8sAggregatorMutation: jest.fn(),
  useUpdateK8sAggregatorMutation: jest.fn(),
  useDeleteK8sAggregatorMutation: jest.fn(),
  usePreviewK8sAggregatorMutation: jest.fn(),
  useGetK8sAggregatorQuery: jest.fn(),
  useListCredentialsQuery: jest.fn(),
}));

// Minimal aggregator body with one fully-populated watcher, satisfying all required watcher fields.
const VALID_WATCHER_CONTENT = {
  localityLbEndpoints: [
    {
      watcher: {
        serviceName: 'my-service',
        kubeconfig: { controlPlaneUrl: 'https://kubernetes.default.svc' },
      },
    },
  ],
};

describe('K8sAggregatorEditor – aggregator ID pattern validation', () => {
  let mockCreate: jest.Mock;
  let mockUpdate: jest.Mock;

  beforeEach(() => {
    mockCreate = jest.fn().mockReturnValue({ unwrap: () => Promise.resolve({}) });
    mockUpdate = jest.fn().mockReturnValue({ unwrap: () => Promise.resolve({}) });

    jest
      .mocked(xdsApiSlice.useCreateK8sAggregatorMutation)
      .mockReturnValue([mockCreate, { isLoading: false }] as any);
    jest
      .mocked(xdsApiSlice.useUpdateK8sAggregatorMutation)
      .mockReturnValue([mockUpdate, { isLoading: false }] as any);
    jest
      .mocked(xdsApiSlice.useDeleteK8sAggregatorMutation)
      .mockReturnValue([jest.fn(), { isLoading: false }] as any);
    jest
      .mocked(xdsApiSlice.usePreviewK8sAggregatorMutation)
      .mockReturnValue([jest.fn(), { isLoading: false }] as any);
    jest.mocked(xdsApiSlice.useGetK8sAggregatorQuery).mockReturnValue({
      // Production API returns content as a YAML string; use jsYaml.dump so the new
      // jsYaml.load branch in parseToFormData is exercised by every test that renders
      // an existing aggregator.
      data: { content: jsYaml.dump(VALID_WATCHER_CONTENT) },
      isLoading: false,
      error: undefined,
    } as any);
    jest.mocked(xdsApiSlice.useListCredentialsQuery).mockReturnValue({ data: [], error: null } as any);
  });

  describe('new aggregator', () => {
    it('rejects a slash ID and shows a validation error', async () => {
      const user = userEvent.setup();
      renderWithProviders(<K8sAggregatorEditor group="foo" isNew />);

      await user.type(screen.getByPlaceholderText('e.g. my-service'), 'foo/bar');
      await user.click(screen.getByRole('button', { name: /^create$/i }));

      await waitFor(() => {
        expect(screen.getByText(/dots allowed, slashes not allowed/i)).toBeInTheDocument();
      });
      expect(mockCreate).not.toHaveBeenCalled();
    });

    it('accepts a dot ID and calls createAggregator', async () => {
      const user = userEvent.setup();
      renderWithProviders(<K8sAggregatorEditor group="foo" isNew />);

      await user.type(screen.getByPlaceholderText('e.g. my-service'), 'foo.bar');
      // Fill the required watcher fields so form submission proceeds past required validation.
      await user.type(screen.getByPlaceholderText('k8s service name'), 'my-service');
      await user.type(screen.getByPlaceholderText('https://kubernetes.default.svc'), 'https://k8s.default.svc');

      await user.click(screen.getByRole('button', { name: /^create$/i }));

      await waitFor(() => {
        expect(mockCreate).toHaveBeenCalled();
      });
      expect(screen.queryByText(/dots allowed, slashes not allowed/i)).not.toBeInTheDocument();
    });
  });

  describe('existing aggregator with a legacy slash ID', () => {
    it('saves without showing a pattern error (backward compat)', async () => {
      const user = userEvent.setup();
      renderWithProviders(<K8sAggregatorEditor group="foo" id="foo/bar" isNew={false} />);

      // Wait for the form to be populated from the fetched data.
      await waitFor(() => {
        expect(screen.getByDisplayValue('foo/bar')).toBeInTheDocument();
      });

      await user.click(screen.getByRole('button', { name: /^edit$/i }));
      await user.click(screen.getByRole('button', { name: /^save$/i }));

      // The update should proceed — the slash ID must not be blocked by the pattern.
      await waitFor(() => {
        expect(mockUpdate).toHaveBeenCalled();
      });
      expect(screen.queryByText(/dots allowed, slashes not allowed/i)).not.toBeInTheDocument();
    });
  });

  describe('sticky action bar', () => {
    it('moves Cancel into the bar and reveals the commit input + Save only while editing', async () => {
      const user = userEvent.setup();
      renderWithProviders(<K8sAggregatorEditor group="foo" id="my-agg" isNew={false} />);

      // Wait for the form to populate from the fetched data.
      await waitFor(() => {
        expect(screen.getByDisplayValue('my-agg')).toBeInTheDocument();
      });

      // Read mode: Edit is shown; the editing action bar (commit input, Save, Cancel) is not rendered.
      expect(screen.getByRole('button', { name: /^edit$/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^save$/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^cancel$/i })).not.toBeInTheDocument();
      expect(screen.queryByPlaceholderText(/Update kubernetes endpoint aggregator/i)).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /^edit$/i }));

      // Editing: the sticky bar carries the commit input plus Cancel and Save (Cancel moved from the top bar).
      expect(screen.getByPlaceholderText(/Update kubernetes endpoint aggregator/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /^save$/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /^cancel$/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^edit$/i })).not.toBeInTheDocument();
    });
  });
});
