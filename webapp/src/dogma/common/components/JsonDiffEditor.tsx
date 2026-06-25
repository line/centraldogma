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
import { DiffEditor, loader } from '@monaco-editor/react';
import { useColorMode } from '@chakra-ui/react';
import { useEffect, useState } from 'react';
import { Loading } from 'dogma/common/components/Loading';

interface JsonDiffEditorProps {
  // The left-hand (baseline) document, e.g. the content before a change.
  original: string;
  // The right-hand (compared) document, e.g. the content after a change.
  modified: string;
  height?: string | number;
}

// Wraps the Monaco diff editor and configures it to use the locally bundled `monaco-editor` package (provided
// by MonacoWebpackPlugin) instead of a CDN, mirroring {@link JsonEditor}.
export const JsonDiffEditor = ({ original, modified, height = '60vh' }: JsonDiffEditorProps) => {
  const { colorMode } = useColorMode();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let active = true;
    (async () => {
      const monaco = await import('monaco-editor');
      loader.config({ monaco });
      await loader.init();
      if (active) {
        setReady(true);
      }
    })();
    return () => {
      active = false;
    };
  }, []);

  if (!ready) {
    return <Loading />;
  }

  return (
    <DiffEditor
      height={height}
      language="json"
      theme={colorMode === 'light' ? 'vs' : 'vs-dark'}
      original={original}
      modified={modified}
      options={{
        readOnly: true,
        renderSideBySide: true,
        minimap: { enabled: false },
        automaticLayout: true,
        scrollBeyondLastLine: false,
        lineNumbersMinChars: 4,
      }}
    />
  );
};
