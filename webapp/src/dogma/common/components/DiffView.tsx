import { Box, ColorMode, Heading, HStack } from '@chakra-ui/react';
import { FileIcon } from 'dogma/common/components/FileIcon';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import ReactDiffViewer from 'react-diff-viewer-continued';
import React from 'react';
import { FileDto } from 'dogma/features/file/FileDto';
import { extensionToLanguageMap } from 'dogma/common/components/editor/FileEditor';
import Prism from 'prismjs';
// Load the language needed
import 'prismjs/components/prism-json';
import 'prismjs/components/prism-json5';
import 'prismjs/components/prism-javascript';
import 'prismjs/components/prism-yaml';
import 'prismjs/components/prism-toml';
import 'prismjs/components/prism-markdown';
import 'prismjs/components/prism-python';
import 'prismjs/components/prism-sass';
import 'prismjs/components/prism-xml-doc';
import 'prismjs/components/prism-docker';
import 'prismjs/themes/prism.css';

function toMap(file: FileDto[]): [string, string][] {
  return file
    .filter((file) => file.type != 'DIRECTORY')
    .map((file) => {
      return [file.path, file.rawContent];
    });
}

function diff(oldData: FileDto[], newData: FileDto[]): Map<string, [string, string]> {
  const oldMap = new Map<string, string>(toMap(oldData));
  const newMap = new Map<string, string>(toMap(newData));
  const difference = new Map<string, [string, string]>();

  newMap.forEach((newFile, path) => {
    const oldFile = oldMap.get(path);
    if (oldFile === newFile) {
      return;
    }

    if (oldFile) {
      difference.set(path, [oldFile, newFile]);
    } else {
      difference.set(path, [null, newFile]);
    }
  });

  oldMap.forEach((oldFile, path) => {
    if (!newMap.has(path)) {
      difference.set(path, [oldFile, null]);
    }
  });

  return difference;
}

function highlightSyntax(path: string, str: string): React.JSX.Element {
  if (!str) {
    return;
  }

  const extension = path.split('.').pop();
  const language = extensionToLanguageMap[extension];
  if (!language) {
    return <pre style={{ display: 'inline' }}>{str}</pre>;
  }

  try {
    return (
      <pre
        style={{ display: 'inline' }}
        dangerouslySetInnerHTML={{ __html: Prism.highlight(str, Prism.languages[language], language) }}
      />
    );
  } catch (e) {
    return <pre style={{ display: 'inline' }}>{str}</pre>;
  }
}

export type DiffMode = 'Split' | 'Unified';

type DiffViewProps = {
  projectName: string;
  repoName: string;
  revision: number;
  oldData: FileDto | FileDto[];
  newData: FileDto | FileDto[];
  diffMode: DiffMode;
  colorMode: ColorMode;
};

const DiffView = ({
  projectName,
  repoName,
  revision,
  oldData,
  newData,
  diffMode,
  colorMode,
}: DiffViewProps) => {
  let oldData0 = oldData || [];
  oldData0 = Array.isArray(oldData0) ? oldData0 : [oldData0];
  let newData0 = newData || [];
  newData0 = Array.isArray(newData0) ? newData0 : [newData0];
  const difference = diff(oldData0, newData0);

  return (
    <Box>
      {Array.from(difference.entries()).map(([path, [oldFile, newFile]]) => (
        <Box key={`${projectName}-${repoName}-${path}-${revision}`} mb={4}>
          <HStack marginBottom={2}>
            <FileIcon fileName={path} />
            <Heading size="sm">
              <ChakraLink href={`/app/projects/${projectName}/repos/${repoName}/files/${revision}${path}`}>
                {path}
              </ChakraLink>
            </Heading>
          </HStack>
          <ReactDiffViewer
            oldValue={oldFile || ''}
            newValue={newFile || ''}
            splitView={diffMode === 'Split'}
            useDarkTheme={colorMode === 'dark'}
            renderContent={(str) => highlightSyntax(path, str)}
          />
        </Box>
      ))}
    </Box>
  );
};

export default DiffView;
