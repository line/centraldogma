// @ts-check

const withBundleAnalyzer = require('@next/bundle-analyzer')({
  enabled: process.env.ANALYZE === 'true',
});

const MonacoWebpackPlugin = require('monaco-editor-webpack-plugin');

const isDev = process.env.NEXT_ENV === 'development';
const nextConfig = {
  productionBrowserSourceMaps: isDev,
  trailingSlash: false,
  output: isDev ? 'standalone' : 'export',
  distDir: 'build/web/',
  images: {
    unoptimized: true,
  },
  transpilePackages: ['monaco-editor'],
  webpack: (config, { isServer }) => {
    if (!isServer) {
      config.plugins.push(
        new MonacoWebpackPlugin({
          languages: ['json', 'javascript', 'typescript'],
          filename: 'static/[name].worker.js',
        })
      );
    }
    return config;
  },

};
module.exports = withBundleAnalyzer(nextConfig);
