import { act, renderHook } from '@testing-library/react';
import { useUrlPagination } from 'dogma/common/components/table/useUrlPagination';
import { XDS_PAGE_SIZES } from 'dogma/features/xds/XdsTypes';

const mockPush = jest.fn();
let mockQuery: Record<string, string | string[]> = {};
let mockIsReady = true;

jest.mock('next/router', () => ({
  useRouter: () => ({
    isReady: mockIsReady,
    query: mockQuery,
    pathname: '/app/xds',
    push: mockPush,
  }),
}));

function render() {
  return renderHook(() => useUrlPagination({ pageSizes: XDS_PAGE_SIZES }));
}

describe('useUrlPagination', () => {
  beforeEach(() => {
    mockPush.mockClear();
    mockQuery = {};
    mockIsReady = true;
  });

  describe('reading state from the URL', () => {
    it('defaults to the first page and the default size when no params are present', () => {
      const { result } = render();
      expect(result.current.pagination).toEqual({ pageIndex: 0, pageSize: 10 });
    });

    it('reads the 1-indexed page param as a 0-indexed pageIndex', () => {
      mockQuery = { page: '3' };
      const { result } = render();
      expect(result.current.pagination.pageIndex).toBe(2);
    });

    it.each(['0', '-1', '1.5', 'abc', ''])('falls back to the first page for a malformed page=%s', (page) => {
      mockQuery = { page };
      const { result } = render();
      expect(result.current.pagination.pageIndex).toBe(0);
    });

    it('reads a page size that the selector offers', () => {
      mockQuery = { pageSize: '20' };
      const { result } = render();
      expect(result.current.pagination.pageSize).toBe(20);
    });

    it.each(['15', '999', 'abc', ''])('ignores an unsupported pageSize=%s', (pageSize) => {
      mockQuery = { pageSize };
      const { result } = render();
      expect(result.current.pagination.pageSize).toBe(10);
    });

    it('does not read the URL until the router is ready', () => {
      mockIsReady = false;
      mockQuery = { page: '3', pageSize: '20' };
      const { result } = render();
      expect(result.current.pagination).toEqual({ pageIndex: 0, pageSize: 10 });
    });
  });

  describe('writing state to the URL', () => {
    it('writes the 1-indexed page with shallow routing', () => {
      const { result } = render();
      act(() => result.current.onPaginationChange({ pageIndex: 1, pageSize: 10 }));
      expect(mockPush).toHaveBeenCalledWith({ pathname: '/app/xds', query: { page: '2' } }, undefined, {
        shallow: true,
      });
    });

    it('omits the page param on the first page and the pageSize param at the default size', () => {
      mockQuery = { page: '4', pageSize: '20' };
      const { result } = render();
      act(() => result.current.onPaginationChange({ pageIndex: 0, pageSize: 10 }));
      expect(mockPush).toHaveBeenCalledWith({ pathname: '/app/xds', query: {} }, undefined, { shallow: true });
    });

    it('writes a non-default page size', () => {
      const { result } = render();
      act(() => result.current.onPaginationChange({ pageIndex: 0, pageSize: 20 }));
      expect(mockPush).toHaveBeenCalledWith({ pathname: '/app/xds', query: { pageSize: '20' } }, undefined, {
        shallow: true,
      });
    });

    it('supports a functional updater based on the current state', () => {
      mockQuery = { page: '2' };
      const { result } = render();
      act(() => result.current.onPaginationChange((old) => ({ ...old, pageIndex: old.pageIndex + 1 })));
      expect(mockPush).toHaveBeenCalledWith({ pathname: '/app/xds', query: { page: '3' } }, undefined, {
        shallow: true,
      });
    });

    it('preserves unrelated query params (e.g. the xDS group and section)', () => {
      mockQuery = { name: 'my-group', type: 'clusters' };
      const { result } = render();
      act(() => result.current.onPaginationChange({ pageIndex: 1, pageSize: 20 }));
      expect(mockPush).toHaveBeenCalledWith(
        { pathname: '/app/xds', query: { name: 'my-group', type: 'clusters', page: '2', pageSize: '20' } },
        undefined,
        { shallow: true },
      );
    });
  });

  describe('reacting to router changes after mount', () => {
    // This is the exact path the back-navigation fix hinges on: on a fresh remount the router starts
    // not-ready with an empty query, then hydrates with the restored params. The effect must defer the read
    // until then and fire on the transition — this test would fail if router.isReady were dropped from the
    // effect dependencies.
    it('performs the deferred read once the router becomes ready', () => {
      mockIsReady = false;
      mockQuery = {};
      const { result, rerender } = render();
      expect(result.current.pagination).toEqual({ pageIndex: 0, pageSize: 10 });

      mockIsReady = true;
      mockQuery = { page: '3', pageSize: '20' };
      rerender();

      expect(result.current.pagination).toEqual({ pageIndex: 2, pageSize: 20 });
    });

    // Guards against the effect being narrowed to only [router.isReady]: it must also re-run when the query
    // params themselves change on an already-mounted instance (browser forward/back within the same page).
    it('re-syncs when the query params change on an already-mounted instance', () => {
      mockQuery = { page: '2' };
      const { result, rerender } = render();
      expect(result.current.pagination.pageIndex).toBe(1);

      mockQuery = {};
      rerender();

      expect(result.current.pagination.pageIndex).toBe(0);
    });
  });
});
