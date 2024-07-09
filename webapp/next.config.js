// @ts-check

const withBundleAnalyzer = require('@next/bundle-analyzer')({
  enabled: process.env.ANALYZE === 'true',
});

const isDev = process.env.NEXT_ENV === 'development';
const nextConfig = {
  // TODO(ikhoon): Replace 'true' with 'isDev' when all features are tested.
  productionBrowserSourceMaps: true,
  trailingSlash: true,
  output: isDev ? 'standalone' : 'export',
  distDir: 'build/web/',
  images: {
    unoptimized: true,
  },
};
module.exports = withBundleAnalyzer(nextConfig);
