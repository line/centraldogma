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

  describe('fields the form must not drop', () => {
    it('round-trips distinctEndpoint, metadataMapping and policy, and sends the loaded revision', async () => {
      const stored = {
        localityLbEndpoints: [
          {
            watcher: {
              serviceName: 'my-service',
              kubeconfig: { controlPlaneUrl: 'https://kubernetes.default.svc' },
              distinctEndpoint: true,
              metadataMapping: [
                { resourceType: 'NODE', entryType: 'LABEL', sourceKey: 'topology.kubernetes.io/zone' },
              ],
            },
          },
        ],
        policy: { overprovisioningFactor: 200, weightedPriorityHealth: true },
      };
      jest.mocked(xdsApiSlice.useGetK8sAggregatorQuery).mockReturnValue({
        data: { content: jsYaml.dump(stored), revision: 7 },
        isLoading: false,
        error: undefined,
      } as any);

      const user = userEvent.setup();
      renderWithProviders(<K8sAggregatorEditor group="foo" id="my-agg" isNew={false} />);
      await waitFor(() => expect(screen.getByDisplayValue('my-agg')).toBeInTheDocument());

      // Every stored field is on screen, not carried invisibly.
      expect(screen.getByLabelText(/distinct endpoint/i)).toBeChecked();
      expect(screen.getByDisplayValue('topology.kubernetes.io/zone')).toBeInTheDocument();
      expect(screen.getByDisplayValue('200')).toBeInTheDocument();
      expect(screen.getByLabelText(/weighted priority health/i)).toBeChecked();

      await user.click(screen.getByRole('button', { name: /^edit$/i }));
      await user.click(screen.getByRole('button', { name: /^save$/i }));

      await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
      const sent = jsYaml.load(mockUpdate.mock.calls[0][0].body) as any;
      const watcher = sent.localityLbEndpoints[0].watcher;
      expect(watcher.distinctEndpoint).toBe(true);
      expect(watcher.metadataMapping).toEqual([
        { resourceType: 'NODE', entryType: 'LABEL', sourceKey: 'topology.kubernetes.io/zone' },
      ]);
      expect(sent.policy).toEqual({ overprovisioningFactor: 200, weightedPriorityHealth: true });
      // The revision the form was loaded at rides with the update so the server can reject a stale save.
      expect(mockUpdate.mock.calls[0][0].revision).toBe('7');
    });

    it('shows a stored drop overload read-only and saves it back unchanged', async () => {
      const stored = {
        localityLbEndpoints: [
          {
            watcher: {
              serviceName: 'my-service',
              kubeconfig: { controlPlaneUrl: 'https://kubernetes.default.svc' },
            },
          },
        ],
        policy: { dropOverloads: [{ category: 'throttle', dropPercentage: { numerator: 30 } }] },
      };
      jest.mocked(xdsApiSlice.useGetK8sAggregatorQuery).mockReturnValue({
        data: { content: jsYaml.dump(stored), revision: 7 },
        isLoading: false,
        error: undefined,
      } as any);

      const user = userEvent.setup();
      renderWithProviders(<K8sAggregatorEditor group="foo" id="my-agg" isNew={false} />);
      await waitFor(() => expect(screen.getByDisplayValue('my-agg')).toBeInTheDocument());

      // Visible, but with no input to change it.
      expect(screen.getByText(/throttle — 30%/)).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /^edit$/i }));
      await user.click(screen.getByRole('button', { name: /^save$/i }));

      await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
      const sent = jsYaml.load(mockUpdate.mock.calls[0][0].body) as any;
      expect(sent.policy.dropOverloads).toEqual([{ category: 'throttle', dropPercentage: { numerator: 30 } }]);
    });

    it('round-trips every field the form renders', async () => {
      // The refactor rewrote each register() path, and a wrong path drops that field silently.
      const stored = {
        localityLbEndpoints: [
          {
            watcher: {
              serviceName: 'my-service',
              portName: 'http',
              kubeconfig: {
                controlPlaneUrl: 'https://kubernetes.default.svc',
                namespace: 'prod',
                credentialId: 'my-credential',
                trustCerts: true,
              },
              metadataMapping: [
                {
                  resourceType: 'POD',
                  entryType: 'ANNOTATION',
                  sourceKeyPrefix: 'topology.kubernetes.io/',
                  metadataNamespace: 'envoy.lb',
                },
              ],
            },
            locality: { region: 'us-east-1', zone: 'us-east-1a', subZone: 'rack-3' },
            priority: 1,
            loadBalancingWeight: 50,
          },
        ],
        policy: { endpointStaleAfter: '30s' },
      };
      jest.mocked(xdsApiSlice.useGetK8sAggregatorQuery).mockReturnValue({
        data: { content: jsYaml.dump(stored), revision: 7 },
        isLoading: false,
        error: undefined,
      } as any);

      const user = userEvent.setup();
      renderWithProviders(<K8sAggregatorEditor group="foo" id="my-agg" isNew={false} />);
      await waitFor(() => expect(screen.getByDisplayValue('my-agg')).toBeInTheDocument());

      await user.click(screen.getByRole('button', { name: /^edit$/i }));
      await user.click(screen.getByRole('button', { name: /^save$/i }));

      await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
      const sent = jsYaml.load(mockUpdate.mock.calls[0][0].body) as any;
      expect(sent.localityLbEndpoints).toEqual(stored.localityLbEndpoints);
      expect(sent.policy).toEqual(stored.policy);
    });

    it('keeps an additional property whose value is empty', async () => {
      const stored = {
        localityLbEndpoints: [
          {
            watcher: {
              serviceName: 'my-service',
              kubeconfig: { controlPlaneUrl: 'https://kubernetes.default.svc' },
              // An empty label value is valid in Kubernetes, so it must survive a save.
              additionalProperties: { nodeIpLabel: '' },
            },
          },
        ],
      };
      jest.mocked(xdsApiSlice.useGetK8sAggregatorQuery).mockReturnValue({
        data: { content: jsYaml.dump(stored), revision: 7 },
        isLoading: false,
        error: undefined,
      } as any);

      const user = userEvent.setup();
      renderWithProviders(<K8sAggregatorEditor group="foo" id="my-agg" isNew={false} />);
      await waitFor(() => expect(screen.getByDisplayValue('my-agg')).toBeInTheDocument());

      await user.click(screen.getByRole('button', { name: /^edit$/i }));
      await user.click(screen.getByRole('button', { name: /^save$/i }));

      await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
      const sent = jsYaml.load(mockUpdate.mock.calls[0][0].body) as any;
      expect(sent.localityLbEndpoints[0].watcher.additionalProperties).toEqual({ nodeIpLabel: '' });
    });

    it('surfaces a 409 as an update conflict', async () => {
      mockUpdate.mockReturnValue({ unwrap: () => Promise.reject({ status: 409 }) });
      const user = userEvent.setup();
      const { store } = renderWithProviders(<K8sAggregatorEditor group="foo" id="my-agg" isNew={false} />);
      await waitFor(() => expect(screen.getByDisplayValue('my-agg')).toBeInTheDocument());

      await user.click(screen.getByRole('button', { name: /^edit$/i }));
      await user.click(screen.getByRole('button', { name: /^save$/i }));

      await waitFor(() => expect(store.getState().notification.title).toBe('Update conflict'));
      expect(screen.getByRole('button', { name: /^save$/i })).toBeInTheDocument();
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
