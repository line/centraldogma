const withBundleAnalyzer = require('@next/bundle-analyzer')({
  enabled: process.env.ANALYZE === 'true',
});
let nextConfig = {
  async rewrites() {
    return [
      {
        source: `/api/:path*`,
        destination: `${process.env.NEXT_PUBLIC_HOST}/api/:path*`,
      },
      {
        source: `/security_enabled`,
        destination: `${process.env.NEXT_PUBLIC_HOST}/security_enabled`,
      },
    ];
  },
};
module.exports = withBundleAnalyzer(nextConfig);
