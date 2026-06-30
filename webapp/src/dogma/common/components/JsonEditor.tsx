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
import Editor, { loader } from '@monaco-editor/react';
import { useColorMode } from '@chakra-ui/react';
import { useEffect, useState } from 'react';
import { Loading } from 'dogma/common/components/Loading';

interface JsonEditorProps {
  value: string;
  onChange?: (value: string) => void;
  readOnly?: boolean;
  height?: string | number;
}

// Wraps the Monaco editor and configures it to use the locally bundled
// `monaco-editor` package (provided by MonacoWebpackPlugin) instead of a CDN.
export const JsonEditor = ({ value, onChange, readOnly = false, height = '60vh' }: JsonEditorProps) => {
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
    <Editor
      height={height}
      defaultLanguage="json"
      theme={colorMode === 'light' ? 'light' : 'vs-dark'}
      value={value}
      onChange={(v) => onChange?.(v ?? '')}
      options={{
        readOnly,
        minimap: { enabled: false },
        automaticLayout: true,
        scrollBeyondLastLine: false,
        formatOnPaste: true,
        formatOnType: true,
        lineNumbersMinChars: 4,
      }}
    />
  );
};
