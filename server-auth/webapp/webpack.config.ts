import path from 'path';

import HtmlWebpackPlugin from 'html-webpack-plugin';
import CompressionWebpackPlugin from 'compression-webpack-plugin';
import { Configuration, DefinePlugin, optimize } from 'webpack';
import WebpackDevServer from 'webpack-dev-server';

declare module 'webpack' {
  interface Configuration {
    devServer?: WebpackDevServer.Configuration;
  }
}

const serverPort = process.env.SERVER_PORT || '8080';

const isDev = !!process.env.WEBPACK_DEV;

const config: Configuration = {
  mode: isDev ? 'development' : 'production',
  devtool: isDev ? 'eval-source-map' : undefined,
  entry: {
    main: ['react-hot-loader/patch', './src/index.tsx'],
  },
  output: {
    path: path.resolve(process.cwd(), './build'),
    // We don't mount to '/' for production build since we want the code to be relocatable.
    publicPath: isDev ? '/' : '',
  },
  module: {
    rules: [
      {
        test: /\.ts(x?)$/,
        exclude: /node_modules/,
        use: [
          {
            loader: 'babel-loader',
            options: {
              presets: [
                [
                  '@babel/env',
                  {
                    modules: false,
                    useBuiltIns: 'entry',
                    corejs: 3,
                    targets: {
                      browsers: ['>1%', 'not ie 11', 'not op_mini all'],
                    },
                  },
                ],
                '@babel/react',
              ],
              plugins: ['react-hot-loader/babel'],
            },
          },
          {
            loader: 'ts-loader',
            options: {
              compilerOptions: {
                noEmit: false,
              },
              transpileOnly: true,
              onlyCompileBundledFiles: true,
              reportFiles: ['src/**/*.{ts,tsx}'],
            },
          },
        ],
      },
      {
        test: /\.css$/,
        use: ['style-loader', 'css-loader'],
      },
      {
        test: /\.(eot|otf|ttf|woff|woff2)$/,
        type: 'asset/resource',
      },
      {
        test: /\.(gif|svg|jpg|png)$/,
        loader: "file-loader",
      }
    ],
  },
  resolve: {
    modules: ['src', 'node_modules'],
    extensions: ['.js', '.jsx', '.ts', '.tsx'],
    mainFields: ['browser', 'module', 'jsnext:main', 'main'],
  },
  plugins: [],
  devServer: {
    historyApiFallback: true,
    hot: true,
    port: 3000,
    proxy: [
      {
        path: '/',
        context: (pathname, req) => true,
        target: `http://127.0.0.1:${serverPort}`,
        changeOrigin: true,
      },
    ],
    client: {
      overlay: {
        warnings: false,
        errors: true,
      },
    },
  },
};

// Configure plugins.
const plugins = config.plugins as any[];
plugins.push(new HtmlWebpackPlugin({
  template: './public/index.html',
  hash: true,
}));

plugins.push(new optimize.LimitChunkCountPlugin({
  maxChunks: 1,
}));

plugins.push(new DefinePlugin({
  'process.env': '{}',
}));

// Do not add CompressionWebpackPlugin on dev
if (!isDev) {
  plugins.push(new CompressionWebpackPlugin({
    test: /\.(js|css|html|svg)$/,
    algorithm: 'gzip',
    filename: '[path][base].gz',
    // If a `Accept-Encoding` is not specified, `DocService` decompresses the compressed content on the fly.
    deleteOriginalAssets: true
  }) as any);
}

export default config;
