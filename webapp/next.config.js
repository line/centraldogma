// @ts-check

const withBundleAnalyzer = require('@next/bundle-analyzer')({
  enabled: process.env.ANALYZE === 'true',
});

const isDev = process.env.NEXT_ENV === 'development';
const nextConfig = {
  productionBrowserSourceMaps: isDev,
  trailingSlash: false,
  output: isDev ? 'standalone' : 'export',
  distDir: 'build/web/',
  images: {
    unoptimized: true,
  },
};
module.exports = withBundleAnalyzer(nextConfig);
