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
import { fireEvent, waitFor } from '@testing-library/react';
import { NewFile } from 'dogma/features/file/NewFile';
import { renderWithProviders } from 'dogma/util/test-utils';

const mockPushUnwrap = jest.fn();
// eslint-disable-next-line @typescript-eslint/no-unused-vars
const mockPush = jest.fn((..._args: unknown[]) => ({ unwrap: mockPushUnwrap }));
const mockGetFilesUnwrap = jest.fn();
const mockGetFiles = jest.fn(({ filePath }: { filePath: string }) => ({
  unwrap: () => mockGetFilesUnwrap(filePath),
}));

// The ESM-only 'yaml' package cannot be loaded by Jest. YAML files are not covered by these tests.
jest.mock('yaml', () => ({ __esModule: true, default: { parse: jest.fn(), stringify: jest.fn() } }));

jest.mock('next/router', () => ({ __esModule: true, default: { push: jest.fn(), back: jest.fn() } }));

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  usePushFileChangesMutation: () => [mockPush, { isLoading: false }],
  useLazyGetFilesQuery: () => [mockGetFiles, { isFetching: false }],
}));

// Monaco cannot mount in JSDOM; replace the editor with a stub that returns fixed content.
jest.mock('dogma/features/file/MonacoLoader', () => ({ useLocalMonaco: () => ({}) }));
jest.mock('@monaco-editor/react', () => ({
  __esModule: true,
  default: function MockEditor({ onMount }: { onMount: (editor: { getValue: () => string }) => void }) {
    onMount({ getValue: () => '{"a": 1}' });
    return null;
  },
}));

function submitNewFile(initialPrefixes = ['a']) {
  const result = renderWithProviders(
    <NewFile projectName="foo" repoName="bar" initialPrefixes={initialPrefixes} />,
  );
  const nameInput = result.getByPlaceholderText(/Type 1\) a file name/);
  fireEvent.change(nameInput, { target: { value: 'b.json' } });
  // react-hook-form picks up the name on blur because the input overrides its onChange handler.
  fireEvent.blur(nameInput);
  fireEvent.change(result.getByPlaceholderText('Add a summary'), { target: { value: 'Add b.json' } });
  fireEvent.click(result.getByRole('button', { name: 'Commit' }));
  return result;
}

describe('NewFile', () => {
  beforeEach(() => {
    mockPush.mockClear();
    mockPushUnwrap.mockReset();
    mockPushUnwrap.mockResolvedValue({});
    mockGetFiles.mockClear();
    mockGetFilesUnwrap.mockReset();
  });

  it('creates the file when it does not exist', async () => {
    // The list API responds with 204 No Content if nothing exists at the path.
    mockGetFilesUnwrap.mockResolvedValue(null);
    submitNewFile();

    await waitFor(() => expect(mockPush).toHaveBeenCalledTimes(1));
    expect(mockGetFiles.mock.calls.map(([args]) => args)).toEqual([
      { projectName: 'foo', repoName: 'bar', filePath: '/a', revision: 'head', withContent: false },
      { projectName: 'foo', repoName: 'bar', filePath: '/a/b.json', revision: 'head', withContent: false },
    ]);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((mockPush.mock.calls[0][0] as any).data.changes).toEqual([
      { path: '/a/b.json', type: 'UPSERT_JSON', rawContent: '{"a": 1}' },
    ]);
  });

  it('asks before overwriting an existing file', async () => {
    // '/a' lists the same entry because 'b.json' is its only child.
    mockGetFilesUnwrap.mockResolvedValue([{ path: '/a/b.json', type: 'JSON' }]);
    const { findByText, getByRole } = submitNewFile();

    expect(await findByText('File already exists')).toBeTruthy();
    expect(mockPush).not.toHaveBeenCalled();

    fireEvent.click(getByRole('button', { name: 'Overwrite' }));
    await waitFor(() => expect(mockPush).toHaveBeenCalledTimes(1));
  });

  it('does not overwrite an existing file when canceled', async () => {
    // '/a' lists the same entry because 'b.json' is its only child.
    mockGetFilesUnwrap.mockResolvedValue([{ path: '/a/b.json', type: 'JSON' }]);
    const { findByText, getAllByRole, queryByText } = submitNewFile();

    expect(await findByText('File already exists')).toBeTruthy();
    // The modal's Cancel button is rendered after the form's one.
    const cancelButtons = getAllByRole('button', { name: 'Cancel' });
    fireEvent.click(cancelButtons[cancelButtons.length - 1]);

    await waitFor(() => expect(queryByText('File already exists')).toBeNull());
    expect(mockPush).not.toHaveBeenCalled();
  });

  it('does not push when a directory exists at the path', async () => {
    // The list API returns the children of a directory, with a trailing slash for a child directory.
    mockGetFilesUnwrap.mockImplementation(async (filePath: string) =>
      filePath === '/a'
        ? [{ path: '/a/b.json/', type: 'DIRECTORY' }]
        : [{ path: '/a/b.json/c.json', type: 'JSON' }],
    );
    const { queryByText, store } = submitNewFile();

    await waitFor(() =>
      expect(store.getState().notification.description).toBe('A directory already exists at /a/b.json'),
    );
    expect(mockPush).not.toHaveBeenCalled();
    expect(queryByText('File already exists')).toBeNull();
  });

  it('does not push when checking the file fails', async () => {
    mockGetFilesUnwrap.mockRejectedValue({ status: 500, data: { message: 'boom' } });
    const { store } = submitNewFile();

    await waitFor(() => expect(store.getState().notification.title).toBe('Failed to create a new file'));
    expect(store.getState().notification.type).toBe('error');
    expect(mockPush).not.toHaveBeenCalled();
  });

  it('does not push when a file exists at a parent path', async () => {
    mockGetFilesUnwrap.mockImplementation(async (filePath: string) =>
      filePath === '/a/b' ? [{ path: '/a/b', type: 'TEXT' }] : null,
    );
    const { queryByText, store } = submitNewFile(['a', 'b']);

    await waitFor(() =>
      expect(store.getState().notification.description).toBe('A file already exists at /a/b'),
    );
    expect(mockPush).not.toHaveBeenCalled();
    expect(queryByText('File already exists')).toBeNull();
  });

  it('creates the file when the parent directory has only one child', async () => {
    // The list API returns the only child of '/a', which must not be mistaken for a file at '/a'.
    mockGetFilesUnwrap.mockImplementation(async (filePath: string) =>
      filePath === '/a' ? [{ path: '/a/c.json', type: 'JSON' }] : null,
    );
    submitNewFile();

    await waitFor(() => expect(mockPush).toHaveBeenCalledTimes(1));
  });

  it('does not check or push when a directory name is invalid', async () => {
    const { store } = submitNewFile(['*']);

    await waitFor(() => expect(store.getState().notification.description).toBe('Invalid directory name: *'));
    expect(mockGetFiles).not.toHaveBeenCalled();
    expect(mockPush).not.toHaveBeenCalled();
  });
});
