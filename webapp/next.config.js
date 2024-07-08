// @ts-check

const withBundleAnalyzer = require('@next/bundle-analyzer')({
  enabled: process.env.ANALYZE === 'true',
});

const isDev = process.env.NEXT_ENV === 'development';
const nextConfig = {
  productionBrowserSourceMaps: isDev,
  trailingSlash: true,
  output: isDev ? 'standalone' : 'export',
  distDir: 'build/web/'
};
module.exports = withBundleAnalyzer(nextConfig);
