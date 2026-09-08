import { useEffect, useState } from 'react';
import { loader, Monaco } from '@monaco-editor/react';

// Worker bundle URLs emitted by MonacoWebpackPlugin (see next.config.js). The filenames are stable
// (no content hash), so both the Monaco worker resolver and the prefetch logic reference them directly.
const WORKER_URLS = {
  editor: '/_next/static/editor.worker.js',
  json: '/_next/static/json.worker.js',
  ts: '/_next/static/ts.worker.js',
};

// Helper to define the worker paths (matches next.config.js)
const setupMonacoEnv = () => {
  if (typeof window !== 'undefined' && !window.MonacoEnvironment) {
    window.MonacoEnvironment = {
      //eslint-disable-next-line @typescript-eslint/no-explicit-any
      getWorkerUrl: function (_moduleId: any, label: string) {
        switch (label) {
          case 'json':
            return WORKER_URLS.json;
          case 'javascript':
          case 'typescript':
            return WORKER_URLS.ts;
          default:
            return WORKER_URLS.editor;
        }
      },
    };
  }
};

// A single shared promise so the monaco-editor chunk is fetched and the loader configured exactly once,
// whether triggered by prefetch or by an editor component mounting.
let monacoPromise: Promise<Monaco> | null = null;

export const loadMonaco = (): Promise<Monaco> => {
  if (typeof window === 'undefined') {
    return Promise.reject(new Error('Monaco can only be loaded in the browser'));
  }
  if (!monacoPromise) {
    setupMonacoEnv();
    monacoPromise = import('monaco-editor')
      .then((monaco) => {
        // Tell the wrapper to use our locally bundled instance instead of the CDN, then fully initialize it.
        loader.config({ monaco });
        return loader.init();
      })
      .catch((err) => {
        // Don't cache a failed load. A transient error (e.g. a chunk-download failure during the idle
        // prefetch) must not poison the singleton, or every later caller would get the same rejection and the
        // editor could never recover. Clear it so the next call retries, then re-throw for the current caller.
        monacoPromise = null;
        throw err;
      });
  }
  return monacoPromise;
};

export const useLocalMonaco = (): Monaco | null => {
  const [monaco, setMonaco] = useState<Monaco | null>(null);

  useEffect(() => {
    let active = true;
    loadMonaco()
      .then((instance) => {
        if (active) {
          setMonaco(instance);
        }
      })
      .catch((err) => {
        console.error('Failed to load local monaco-editor:', err);
      });
    return () => {
      active = false;
    };
  }, []);

  return monaco;
};

// Warm the browser cache for the language worker bundles. `rel="prefetch"` is a low-priority hint, so it
// never competes with resources the current page actually needs. Idempotent: skips URLs already linked.
const prefetchWorkers = () => {
  for (const href of Object.values(WORKER_URLS)) {
    if (document.head.querySelector(`link[rel="prefetch"][href="${href}"]`)) {
      continue;
    }
    const link = document.createElement('link');
    link.rel = 'prefetch';
    // Deliberately omit `as="worker"`: a plain prefetch lands the response in the HTTP cache and is reused
    // by the later `new Worker(url)` fetch. Setting `as="worker"` is inconsistently honored across browsers
    // and can make the prefetched entry ineligible for reuse, causing the worker to be downloaded twice.
    link.href = href;
    document.head.appendChild(link);
  }
};

// Kick off Monaco loading ahead of the first file view so the editor opens instantly. Safe to call
// repeatedly: loadMonaco() is a singleton and prefetchWorkers() is idempotent.
export const prefetchMonaco = () => {
  if (typeof window === 'undefined') {
    return;
  }
  prefetchWorkers();
  loadMonaco().catch((err) => {
    console.error('Failed to prefetch monaco-editor:', err);
  });
};

type IdleWindow = Window & {
  requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number;
  cancelIdleCallback?: (handle: number) => void;
};

// Prefetch Monaco during browser idle time once `enabled` is true. The download runs asynchronously and at
// low priority, so it never blocks the current page — Monaco is simply ready by the time a file is opened.
export const useMonacoPrefetch = (enabled: boolean) => {
  useEffect(() => {
    if (!enabled || typeof window === 'undefined') {
      return;
    }
    const idleWindow = window as IdleWindow;
    let idleHandle = 0;
    let timeoutHandle = 0;
    if (idleWindow.requestIdleCallback) {
      idleHandle = idleWindow.requestIdleCallback(() => prefetchMonaco(), { timeout: 3000 });
    } else {
      // Browsers without requestIdleCallback (e.g. Safari) fall back to a short deferral.
      timeoutHandle = window.setTimeout(() => prefetchMonaco(), 1500);
    }
    return () => {
      if (idleHandle && idleWindow.cancelIdleCallback) {
        idleWindow.cancelIdleCallback(idleHandle);
      }
      if (timeoutHandle) {
        clearTimeout(timeoutHandle);
      }
    };
  }, [enabled]);
};
