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
  // `npm run build` and `next dev` share this directory, so a gradle build (which runs the former as part
  // of :webapp:runTestServer) wipes a running dev server's manifests. Point the dev server elsewhere.
  distDir: process.env.NEXT_DIST_DIR || 'build/web/',
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
  async redirects() {
    return [
      {
        source: '/:path*',
        has: [{ type: 'host', value: 'localhost' }],
        destination: 'http://127.0.0.1:3000/:path*',
        permanent: false,
      },
    ];
  },
};
module.exports = withBundleAnalyzer(nextConfig);
