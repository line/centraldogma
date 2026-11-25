import { functionalUpdate, OnChangeFn, PaginationState, Table } from '@tanstack/react-table';
import { useRouter } from 'next/router';
import { useCallback, useEffect, useRef, useState } from 'react';

const DEFAULT_PAGE_SIZE = 10;

// Parses the 1-indexed `page` query param into react-table's 0-indexed pageIndex, falling back to the first
// page for a missing or malformed value. An out-of-range (too large) value is left as-is here and corrected by
// useClampPageIndex once the row count is known.
function parsePageIndex(value: string | string[] | undefined): number {
  const n = Number(value);
  return Number.isInteger(n) && n > 0 ? n - 1 : 0;
}

// Parses the `pageSize` query param, accepting it only when it is one of the offered sizes so a hand-edited URL
// cannot force a size the selector could not otherwise display.
function parsePageSize(
  value: string | string[] | undefined,
  pageSizes: readonly number[],
  defaultPageSize: number,
): number {
  const n = Number(value);
  return pageSizes.includes(n) ? n : defaultPageSize;
}

export interface UseUrlPaginationOptions {
  // The page sizes offered by the size selector; a `pageSize` query param outside this set is ignored. May be
  // an inline array — the hook does not depend on its identity.
  pageSizes: readonly number[];
  // The page size used when the URL carries no (valid) `pageSize`. Should be one of `pageSizes`.
  defaultPageSize?: number;
}

export interface UrlPagination {
  pagination: PaginationState;
  onPaginationChange: OnChangeFn<PaginationState>;
}

/**
 * Mirrors a TanStack Table's pagination into the URL query (`page`, `pageSize`) so it survives navigation:
 * opening a row's detail page and pressing the browser Back button restores the same page and page size
 * instead of resetting to the first page. `page` is 1-indexed in the URL, and both params are omitted while at
 * their defaults to keep URLs clean. Routing is shallow, so persisting the state never refetches data.
 *
 * Consumers must wire the returned `pagination`/`onPaginationChange` into `useReactTable` as controlled state
 * and set `autoResetPageIndex: false`; otherwise an asynchronous data load would reset the restored page back
 * to the first one, reintroducing the very bug this hook fixes. Because auto-reset is off, also call
 * {@link useClampPageIndex} so a stale page cannot outlive a shrinking data set.
 */
export function useUrlPagination({
  pageSizes,
  defaultPageSize = DEFAULT_PAGE_SIZE,
}: UseUrlPaginationOptions): UrlPagination {
  const router = useRouter();
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: defaultPageSize });

  // Hold the latest valid sizes in a ref so the URL-sync effect does not take `pageSizes` as a dependency.
  // A caller passing an inline array (a new reference every render) would otherwise re-run the effect on every
  // render — and since the effect calls setPagination with a fresh object, that would be an infinite loop.
  const pageSizesRef = useRef(pageSizes);
  pageSizesRef.current = pageSizes;

  const onPaginationChange = useCallback<OnChangeFn<PaginationState>>(
    (updater) => {
      const next = functionalUpdate(updater, pagination);
      setPagination(next);
      const query = { ...router.query };
      if (next.pageIndex === 0) {
        delete query.page;
      } else {
        query.page = String(next.pageIndex + 1);
      }
      if (next.pageSize === defaultPageSize) {
        delete query.pageSize;
      } else {
        query.pageSize = String(next.pageSize);
      }
      router.push({ pathname: router.pathname, query }, undefined, { shallow: true });
    },
    [pagination, router, defaultPageSize],
  );

  // Sync from the URL once the router is ready and whenever the params change (including the browser Back
  // button), which is what restores the page after returning from a detail page.
  useEffect(() => {
    if (!router.isReady) {
      return;
    }
    setPagination({
      pageIndex: parsePageIndex(router.query?.page),
      pageSize: parsePageSize(router.query?.pageSize, pageSizesRef.current, defaultPageSize),
    });
  }, [router.isReady, router.query?.page, router.query?.pageSize, defaultPageSize]);

  return { pagination, onPaginationChange };
}

/**
 * Corrects the table's page index when it falls outside the available pages — e.g. a hand-edited or stale
 * `?page=` URL, or a data set that shrank (a resource was deleted, or a filter narrowed the results) while a
 * later page was selected. Without this, a controlled table with `autoResetPageIndex: false` keeps the stale
 * index and renders an empty body under a nonsensical "Page 5 of 3". Clamps to the last available page and
 * routes the change through the table so it is mirrored back into the URL by {@link useUrlPagination}.
 */
export function useClampPageIndex<Data>(table: Table<Data>): void {
  const { pageIndex } = table.getState().pagination;
  const pageCount = table.getPageCount();
  useEffect(() => {
    if (pageCount > 0 && pageIndex > pageCount - 1) {
      table.setPageIndex(pageCount - 1);
    }
  }, [table, pageIndex, pageCount]);
}
