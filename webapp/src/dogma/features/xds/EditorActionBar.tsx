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
import { ReactNode } from 'react';
import { Box, Flex, HStack, Input, Spacer, useColorModeValue } from '@chakra-ui/react';

interface EditorActionBarProps {
  commitSummary: string;
  onCommitSummaryChange: (value: string) => void;
  commitPlaceholder: string;
  // Constrains the bar to the form width (the K8s aggregator form uses "3xl"). Defaults to the full
  // width of the content area (used by the full-width YAML resource editor).
  maxW?: string;
  // Action buttons (Cancel/Save/Preview/Create), rendered right-aligned.
  children: ReactNode;
}

// A sticky footer that pins the commit-summary input and action buttons to the bottom of the viewport
// while editing, so Save/Create stays reachable without scrolling past a tall editor or form. It relies
// on the page (body) being the scroll container, matching the rest of the app.
export const EditorActionBar = ({
  commitSummary,
  onCommitSummaryChange,
  commitPlaceholder,
  maxW,
  children,
}: EditorActionBarProps) => {
  // Match the default Chakra body background (the app uses the stock theme, no extendTheme) so the bar
  // opaquely covers any editor/form content it overlaps in both color modes.
  const bg = useColorModeValue('white', 'gray.800');
  const borderColor = useColorModeValue('gray.200', 'gray.700');
  return (
    <Box
      position="sticky"
      bottom={0}
      zIndex={1}
      mt={4}
      py={3}
      bg={bg}
      borderTopWidth="1px"
      borderColor={borderColor}
      maxW={maxW}
    >
      <Flex align="center" gap={3}>
        {/* The descriptive placeholder ("Update cluster: ...") stands in for a visible label so the bar
            stays a single row; aria-label keeps it accessible. */}
        <Input
          aria-label="Commit summary"
          maxW="md"
          value={commitSummary}
          onChange={(e) => onCommitSummaryChange(e.target.value)}
          placeholder={commitPlaceholder}
        />
        <Spacer />
        <HStack spacing={3} flexShrink={0}>
          {children}
        </HStack>
      </Flex>
    </Box>
  );
};
