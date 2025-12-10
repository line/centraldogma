import { Monaco } from '@monaco-editor/react';

/**
 * Register JSON5 language in Monaco Editor.
 * Example of supported JSON5 features:
 * ```json5
 * {
 *   // comments
 *   unquoted: 'and you can quote me on that',
 *   singleQuotes: 'I can use "double quotes" here',
 *   lineBreaks: "Look, Mom! \
 * No \\n's!",
 *   leadingDecimalPoint: .8675309,
 *   positiveSign: +1,
 *   trailingComma: 'in objects', andIn: ['arrays',],
 *   "backwardsCompatible": "with JSON",
 * }
 * ```
 *
 * The following JSON5 features are NOT supported due to limitations in Jackson ObjectMapper:
 * - Hexadecimal integers are not supported. (e.g., 0xdecaf)</li>
 * - Trailing decimal points are not supported. (e.g., 8675309.)</li>
 */
export function registerJson5Language(monaco: Monaco) {
  monaco.languages.register({ id: 'json5' });

  monaco.languages.setMonarchTokensProvider('json5', {
    tokenizer: {
      root: [
        // Comments
        [/(\/\/.*$)/, 'comment'],
        [/(\/\*[^]*?\*\/)/, 'comment'],

        // Single-quoted strings
        [/'/, { token: 'string.quote', bracket: '@open', next: '@sstring' }],

        // Double-quoted strings
        [/"/, { token: 'string.quote', bracket: '@open', next: '@string' }],

        // Non-numeric special numbers (NaN, Infinity) with optional sign
        [/[+-]?(Infinity|NaN)\b/, 'number'],

        // Numbers:
        // - integer:        +123, -123, 123
        // - decimal:        123.45, +123.45, -123.45
        // - leading dot:    .5, +.5, -.5
        // - exponent:       1e10, 1.5e-3, .5e+2
        //
        // Does NOT match "1." (trailing decimal)
        [/[+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?\b/, 'number'],

        // Booleans / null
        [/\b(true|false|null)\b/, 'keyword'],

        // Unquoted keys: identifier followed by colon
        [/([^\s:\{\}\[\]",]+)(\s*)(:)/, ['key', '', 'delimiter']],

        // Delimiters
        [/[{}\[\],]/, 'delimiter.bracket'],
      ],

      // Double-quoted string state (multi-line)
      string: [
        [/[^\\"]+/, 'string'],
        [/\\./, 'string.escape'],
        [/"/, { token: 'string.quote', bracket: '@close', next: '@pop' }],
      ],
      // Single-quoted string state (multi-line)
      sstring: [
        [/[^\\']+/, 'string'],
        [/\\./, 'string.escape'],
        [/'/, { token: 'string.quote', bracket: '@close', next: '@pop' }],
      ],
    },
  });

  monaco.languages.setLanguageConfiguration('json5', {
    brackets: [
      ['{', '}'],
      ['[', ']'],
    ],
    autoClosingPairs: [
      { open: '{', close: '}' },
      { open: '[', close: ']' },
      { open: '"', close: '"' },
      { open: "'", close: "'" },
    ],
    surroundingPairs: [
      { open: '{', close: '}' },
      { open: '[', close: ']' },
      { open: '"', close: '"' },
      { open: "'", close: "'" },
    ],
    comments: {
      lineComment: '//',
      blockComment: ['/*', '*/'],
    },
  });
}
