import { renderHook } from '@testing-library/react';
import { useGetProjectsQuery, useGetReposQuery } from 'dogma/features/api/apiSlice';
import {
  PROJECT_READ_ONLY_HINT,
  REPO_READ_ONLY_HINT,
  useProjectReadOnly,
  useReadOnly,
  useRepoReadOnly,
} from 'dogma/features/repo/useReadOnly';

jest.mock('dogma/features/api/apiSlice', () => ({
  useGetProjectsQuery: jest.fn(),
  useGetReposQuery: jest.fn(),
}));

const givenProjects = (data: unknown) => (useGetProjectsQuery as jest.Mock).mockReturnValue({ data });
const givenRepos = (data: unknown) => (useGetReposQuery as jest.Mock).mockReturnValue({ data });

beforeEach(() => {
  jest.clearAllMocks();
  givenProjects(undefined);
  givenRepos(undefined);
});

describe('useProjectReadOnly', () => {
  it('reports a read-only project', () => {
    givenProjects([{ name: 'foo', status: 'READ_ONLY' }]);
    const { result } = renderHook(() => useProjectReadOnly('foo'));
    expect(result.current).toBe(true);
  });

  it('reports a writable project', () => {
    givenProjects([{ name: 'foo', status: 'WRITABLE' }]);
    const { result } = renderHook(() => useProjectReadOnly('foo'));
    expect(result.current).toBe(false);
  });

  // A write action must never be disabled just because the status has not arrived yet.
  it('is not read-only while the projects are still being fetched', () => {
    givenProjects(undefined);
    const { result } = renderHook(() => useProjectReadOnly('foo'));
    expect(result.current).toBe(false);
  });

  it('is not read-only when the project is missing from the response', () => {
    givenProjects([{ name: 'other', status: 'READ_ONLY' }]);
    const { result } = renderHook(() => useProjectReadOnly('foo'));
    expect(result.current).toBe(false);
  });

  it.each(['dogma', '@internal'])('skips the query for the internal project %s', (projectName) => {
    const { result } = renderHook(() => useProjectReadOnly(projectName));
    expect(result.current).toBe(false);
    expect(useGetProjectsQuery).toHaveBeenCalledWith({ systemAdmin: false }, { skip: true });
  });

  it('skips the query until the project name is known', () => {
    const { result } = renderHook(() => useProjectReadOnly(''));
    expect(result.current).toBe(false);
    expect(useGetProjectsQuery).toHaveBeenCalledWith({ systemAdmin: false }, { skip: true });
  });
});

describe('useRepoReadOnly', () => {
  it('reports a read-only repository', () => {
    givenRepos([{ name: 'bar', status: 'READ_ONLY' }]);
    const { result } = renderHook(() => useRepoReadOnly('foo', 'bar'));
    expect(result.current).toBe(true);
  });

  it('reports a writable repository', () => {
    givenRepos([{ name: 'bar', status: 'WRITABLE' }]);
    const { result } = renderHook(() => useRepoReadOnly('foo', 'bar'));
    expect(result.current).toBe(false);
  });

  it('is not read-only while the repositories are still being fetched', () => {
    givenRepos(undefined);
    const { result } = renderHook(() => useRepoReadOnly('foo', 'bar'));
    expect(result.current).toBe(false);
  });

  it('skips the query for a repository of an internal project', () => {
    const { result } = renderHook(() => useRepoReadOnly('dogma', 'bar'));
    expect(result.current).toBe(false);
    expect(useGetReposQuery).toHaveBeenCalledWith('dogma', { skip: true });
  });
});

describe('useReadOnly', () => {
  it('prefers the project hint when the whole project is read-only', () => {
    givenProjects([{ name: 'foo', status: 'READ_ONLY' }]);
    // The server returns the effective status, so a repository of a read-only project is read-only too.
    givenRepos([{ name: 'bar', status: 'READ_ONLY' }]);
    const { result } = renderHook(() => useReadOnly('foo', 'bar'));
    expect(result.current).toEqual([true, PROJECT_READ_ONLY_HINT]);
  });

  it('uses the repository hint when only the repository is read-only', () => {
    givenProjects([{ name: 'foo', status: 'WRITABLE' }]);
    givenRepos([{ name: 'bar', status: 'READ_ONLY' }]);
    const { result } = renderHook(() => useReadOnly('foo', 'bar'));
    expect(result.current).toEqual([true, REPO_READ_ONLY_HINT]);
  });

  it('is not read-only when both the project and the repository are writable', () => {
    givenProjects([{ name: 'foo', status: 'WRITABLE' }]);
    givenRepos([{ name: 'bar', status: 'WRITABLE' }]);
    const { result } = renderHook(() => useReadOnly('foo', 'bar'));
    expect(result.current[0]).toBe(false);
  });
});
