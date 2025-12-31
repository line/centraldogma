import { useEffect, useState } from 'react';
import { loader } from '@monaco-editor/react';

// Helper to define the worker paths (matches next.config.js)
const setupMonacoEnv = () => {
  if (typeof window !== 'undefined' && !window.MonacoEnvironment) {
    window.MonacoEnvironment = {
      //eslint-disable-next-line @typescript-eslint/no-explicit-any
      getWorkerUrl: function (_moduleId: any, label: string) {
        switch (label) {
          case 'json':
            return '/_next/static/json.worker.js';
          case 'javascript':
          case 'typescript':
            return '/_next/static/ts.worker.js';
          default:
            return '/_next/static/editor.worker.js';
        }
      },
    };
  }
};

export const useLocalMonaco = () => {
  //eslint-disable-next-line @typescript-eslint/no-explicit-any
  const [monaco, setMonaco] = useState<any>(null);

  useEffect(() => {
    if (typeof window !== 'undefined') {
      setupMonacoEnv();

      import('monaco-editor')
        .then((monacoInstance) => {
          // Tell the wrapper to use our local instance instead of CDN
          loader.config({ monaco: monacoInstance });

          setMonaco(monacoInstance);
        })
        .catch((err) => {
          console.error('Failed to load local monaco-editor:', err);
        });
    }
  }, []);

  return monaco;
};
