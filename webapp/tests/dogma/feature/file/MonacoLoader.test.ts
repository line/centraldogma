import { renderHook } from '@testing-library/react';
import { prefetchMonaco, useMonacoPrefetch } from 'dogma/features/file/MonacoLoader';

// The real monaco-editor package is a multi-megabyte browser bundle; mock it so the loader logic can be
// tested in jsdom without loading it.
jest.mock('monaco-editor', () => ({ __esModule: true, editor: {}, languages: {} }), { virtual: true });

jest.mock('@monaco-editor/react', () => ({
  loader: {
    config: jest.fn(),
    init: jest.fn().mockResolvedValue({ editor: {} }),
  },
}));

describe('MonacoLoader', () => {
  beforeEach(() => {
    document.head.innerHTML = '';
  });

  describe('loadMonaco', () => {
    it('imports monaco and configures the loader exactly once across calls', async () => {
      // Isolate the module so its `monacoPromise` singleton (and the mock call counts) start fresh, without
      // resetting the shared React instance the hook tests rely on.
      await jest.isolateModulesAsync(async () => {
        const { loadMonaco } = require('dogma/features/file/MonacoLoader');
        const { loader } = require('@monaco-editor/react');

        const first = loadMonaco();
        const second = loadMonaco();

        // Same shared promise — not a new import/config per call.
        expect(first).toBe(second);

        await first;

        expect(loader.config).toHaveBeenCalledTimes(1);
        expect(loader.init).toHaveBeenCalledTimes(1);
      });
    });

    it('retries after a failed load instead of caching the rejection', async () => {
      await jest.isolateModulesAsync(async () => {
        const { loadMonaco } = require('dogma/features/file/MonacoLoader');
        const { loader } = require('@monaco-editor/react');

        // The `@monaco-editor/react` mock is shared across `isolateModulesAsync` blocks, so clear its call
        // count here to keep the assertions below independent of other tests' invocations.
        loader.init.mockClear();
        // Simulate a transient load failure (e.g. a chunk-download error) on the first attempt, then success.
        loader.init.mockRejectedValueOnce(new Error('network blip')).mockResolvedValue({ editor: {} });

        // The first attempt rejects...
        await expect(loadMonaco()).rejects.toThrow('network blip');

        // ...and the failure must not be cached: the next call retries and succeeds rather than returning the
        // same rejected promise, so the editor can recover once the network does.
        await expect(loadMonaco()).resolves.toEqual({ editor: {} });
        expect(loader.init).toHaveBeenCalledTimes(2);
      });
    });
  });

  describe('prefetchMonaco', () => {
    const expectedHrefs = [
      '/_next/static/editor.worker.js',
      '/_next/static/json.worker.js',
      '/_next/static/ts.worker.js',
    ];

    it('injects a prefetch link for every worker bundle', () => {
      prefetchMonaco();

      const links = Array.from(document.head.querySelectorAll('link[rel="prefetch"]')) as HTMLLinkElement[];
      const hrefs = links.map((link) => link.getAttribute('href'));

      expect(hrefs).toEqual(expect.arrayContaining(expectedHrefs));
      expect(links).toHaveLength(expectedHrefs.length);
      // A plain prefetch (no `as`) is used so the response is reused by the later worker fetch; see
      // prefetchWorkers().
      expect(links.every((link) => link.getAttribute('as') === null)).toBe(true);
    });

    it('does not duplicate prefetch links when called repeatedly', () => {
      prefetchMonaco();
      prefetchMonaco();

      expect(document.head.querySelectorAll('link[rel="prefetch"]')).toHaveLength(expectedHrefs.length);
    });
  });

  describe('useMonacoPrefetch', () => {
    beforeEach(() => {
      jest.useFakeTimers();
    });

    afterEach(() => {
      jest.useRealTimers();
    });

    it('schedules a prefetch during idle time when enabled', () => {
      renderHook(() => useMonacoPrefetch(true));

      // Nothing prefetched synchronously — it is deferred until the browser is idle.
      expect(document.head.querySelectorAll('link[rel="prefetch"]')).toHaveLength(0);

      // jsdom has no requestIdleCallback, so the setTimeout fallback fires the prefetch.
      jest.runOnlyPendingTimers();

      expect(document.head.querySelectorAll('link[rel="prefetch"]').length).toBeGreaterThan(0);
    });

    it('does nothing when disabled', () => {
      renderHook(() => useMonacoPrefetch(false));
      jest.runOnlyPendingTimers();

      expect(document.head.querySelectorAll('link[rel="prefetch"]')).toHaveLength(0);
    });

    it('cancels the pending prefetch on unmount', () => {
      const { unmount } = renderHook(() => useMonacoPrefetch(true));
      unmount();
      jest.runOnlyPendingTimers();

      expect(document.head.querySelectorAll('link[rel="prefetch"]')).toHaveLength(0);
    });
  });
});
