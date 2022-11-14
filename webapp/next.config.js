const withBundleAnalyzer = require('@next/bundle-analyzer')({
  enabled: process.env.ANALYZE === 'true',
});
let nextConfig = {
  async redirects() {
    return [
      {
        source: '/',
        destination: '/app/projects',
        permanent: true,
      },
    ];
  },
};
module.exports = withBundleAnalyzer(nextConfig);
