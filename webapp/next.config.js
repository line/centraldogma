const withBundleAnalyzer = require('@next/bundle-analyzer')({
  enabled: process.env.ANALYZE === 'true',
});
const nextConfig = {
  productionBrowserSourceMaps: process.env.NEXT_ENV === 'development',
  trailingSlash: true,
};
module.exports = withBundleAnalyzer(nextConfig);
