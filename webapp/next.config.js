// @ts-check

const withBundleAnalyzer = require('@next/bundle-analyzer')({
  enabled: process.env.ANALYZE === 'true',
});

const nextConfig = {
  productionBrowserSourceMaps: process.env.NEXT_ENV === 'development',
  trailingSlash: true,
  output: process.env.NEXT_PUBLIC_HOST == null ? 'export' : 'standalone',
  distDir: 'build/web/'
};
module.exports = withBundleAnalyzer(nextConfig);
