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
import { CommitForm, CommitFormProps } from 'dogma/common/components/CommitForm';
import { renderWithProviders } from 'dogma/util/test-utils';

const mockUnwrap = jest.fn();
// eslint-disable-next-line @typescript-eslint/no-unused-vars
const mockPush = jest.fn((..._args: unknown[]) => ({ unwrap: mockUnwrap }));

// The ESM-only 'yaml' package cannot be loaded by Jest. YAML files are not covered by these tests.
jest.mock('yaml', () => ({ __esModule: true, default: { parse: jest.fn(), stringify: jest.fn() } }));

const mockGetFileUnwrap = jest.fn();
// eslint-disable-next-line @typescript-eslint/no-unused-vars
const mockGetFile = jest.fn((..._args: unknown[]) => ({ unwrap: mockGetFileUnwrap }));

jest.mock('dogma/features/api/apiSlice', () => {
  const actual = jest.requireActual('dogma/features/api/apiSlice');
  return {
    ...actual,
    usePushFileChangesMutation: () => [mockPush, { isLoading: false }],
    useLazyGetFileContentQuery: () => [mockGetFile, { isFetching: false }],
  };
});

function renderCommitForm(props: Partial<CommitFormProps>) {
  const defaultProps: CommitFormProps = {
    projectName: 'foo',
    repoName: 'bar',
    path: '/a/b.json',
    name: 'b.json',
    content: () => '{"a": 1}',
    readOnly: false,
    setReadOnly: jest.fn(),
    switchMode: jest.fn(),
    handleTabChange: jest.fn(),
  };
  const merged = { ...defaultProps, ...props };
  const result = renderWithProviders(<CommitForm {...merged} />);
  fireEvent.change(result.getByPlaceholderText('Add a summary'), { target: { value: 'Commit' } });
  fireEvent.click(result.getByRole('button', { name: 'Commit' }));
  return merged;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function pushedChanges(): any[] {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return (mockPush.mock.calls[0][0] as any).data.changes;
}

describe('CommitForm', () => {
  beforeEach(() => {
    mockPush.mockClear();
    mockUnwrap.mockReset();
    mockUnwrap.mockResolvedValue({});
    mockGetFile.mockClear();
    mockGetFileUnwrap.mockReset();
    // Nothing exists at the new path by default.
    mockGetFileUnwrap.mockRejectedValue({ status: 404, data: {} });
  });

  it('upserts the file when it is not renamed', async () => {
    const onRenamed = jest.fn();
    renderCommitForm({ newName: 'b.json', onRenamed });

    await waitFor(() => expect(mockPush).toHaveBeenCalledTimes(1));
    expect(pushedChanges()).toEqual([{ path: '/a/b.json', type: 'UPSERT_JSON', rawContent: '{"a": 1}' }]);
    expect(onRenamed).not.toHaveBeenCalled();
  });

  it('renames and upserts the file in a single commit', async () => {
    const onRenamed = jest.fn();
    renderCommitForm({ newName: 'c.json5', onRenamed });

    await waitFor(() => expect(mockPush).toHaveBeenCalledTimes(1));
    expect(pushedChanges()).toEqual([
      { path: '/a/b.json', type: 'RENAME', content: '/a/c.json5' },
      { path: '/a/c.json5', type: 'UPSERT_JSON', rawContent: '{"a": 1}' },
    ]);
    await waitFor(() => expect(onRenamed).toHaveBeenCalledWith('/a/c.json5'));
  });

  it('updates the old file before renaming when the entry type changes', async () => {
    renderCommitForm({ path: '/a/b.txt', name: 'b.txt', newName: 'b.json' });

    await waitFor(() => expect(mockPush).toHaveBeenCalledTimes(1));
    expect(pushedChanges()).toEqual([
      { path: '/a/b.txt', type: 'UPSERT_TEXT', rawContent: '{"a": 1}' },
      { path: '/a/b.txt', type: 'RENAME', content: '/a/b.json' },
      { path: '/a/b.json', type: 'UPSERT_JSON', rawContent: '{"a": 1}' },
    ]);
  });

  it('renames a JSON file to a text file without updating the old file', async () => {
    renderCommitForm({ newName: 'b.txt', content: () => 'plain text' });

    await waitFor(() => expect(mockPush).toHaveBeenCalledTimes(1));
    expect(pushedChanges()).toEqual([
      { path: '/a/b.json', type: 'RENAME', content: '/a/b.txt' },
      { path: '/a/b.txt', type: 'UPSERT_TEXT', rawContent: 'plain text' },
    ]);
  });

  it('does not push when a file exists at the new path', async () => {
    mockGetFileUnwrap.mockResolvedValue({ path: '/a/c.json' });
    renderCommitForm({ newName: 'c.json' });

    await waitFor(() => expect(mockGetFile).toHaveBeenCalledTimes(1));
    expect(mockGetFile.mock.calls[0][0]).toMatchObject({ filePath: '/a/c.json', revision: 'head' });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(mockPush).not.toHaveBeenCalled();
  });

  it('does not push when a directory exists at the new path', async () => {
    // The server responds with 204 No Content for a directory.
    mockGetFileUnwrap.mockResolvedValue(undefined);
    renderCommitForm({ newName: 'c' });

    await waitFor(() => expect(mockGetFile).toHaveBeenCalledTimes(1));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(mockPush).not.toHaveBeenCalled();
  });

  it('does not push when the new file name is empty', async () => {
    renderCommitForm({ newName: '', content: () => '{"a": 2}' });

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(mockGetFile).not.toHaveBeenCalled();
    expect(mockPush).not.toHaveBeenCalled();
  });

  it('does not check the path when the file is not renamed', async () => {
    renderCommitForm({ newName: 'b.json' });

    await waitFor(() => expect(mockPush).toHaveBeenCalledTimes(1));
    expect(mockGetFile).not.toHaveBeenCalled();
  });

  it('does not push when the new file name is invalid', async () => {
    renderCommitForm({ newName: 'c/d.json' });

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(mockPush).not.toHaveBeenCalled();
  });

  it('does not push when the content is invalid for the new file type', async () => {
    renderCommitForm({ path: '/a/b.txt', name: 'b.txt', newName: 'b.json', content: () => 'not json' });

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(mockPush).not.toHaveBeenCalled();
  });
});
