const defaultConfig = require('../settings/eslint/eslintrc');

module.exports = {
  ...defaultConfig,
  ignorePatterns: [...defaultConfig.ignorePatterns],
  extends: ['next', 'prettier', 'plugin:@next/next/recommended'],
  parserOptions: {
    project: './tsconfig.json',
  },
};
