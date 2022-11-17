const withBundleAnalyzer = require('@next/bundle-analyzer')({
  enabled: process.env.ANALYZE === 'true',
});
const nextConfig = {
  trailingSlash: true,
};
module.exports = withBundleAnalyzer(nextConfig);
