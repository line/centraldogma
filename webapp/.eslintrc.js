const defaultConfig = require('../settings/eslint/eslintrc');

module.exports = {
  ...defaultConfig,
  ignorePatterns: [...defaultConfig.ignorePatterns, '.next/'],
  extends: [
    'next',
    'plugin:@next/next/recommended',
    'eslint:recommended',
    'prettier',
  ],
  parserOptions: {
    project: './tsconfig.json',
  },
};
