import path from 'path';
import webpack from 'webpack';
import 'webpack-dev-server';
import HtmlWebpackPlugin from 'html-webpack-plugin';

const centralDogmaPort = process.env.CENTRALDOGMA_PORT || '36462';
const isDev = !!process.env.WEBPACK_DEV;

const config: webpack.Configuration = {
  mode: isDev ? 'development' : 'production',
  devtool: 'inline-source-map',
  entry: './src/index.tsx',
  module: {
    rules: [
      {
        test: /\.tsx?$/,
        use: 'ts-loader',
        exclude: /node_modules/,
      },
    ],
  },
  resolve: {
    modules: [path.resolve(__dirname, 'src'), 'node_modules'],
    extensions: ['.tsx', '.ts', '.js'],
  },
  plugins: [
    new HtmlWebpackPlugin({
      template: './src/index.html',
      hash: true,
    }),
  ],
  output: {
    publicPath: '/',
    filename: 'asserts/[name].js',
    path: path.resolve(__dirname, './build/web'),
    chunkFilename: '[id].[chunkhash].js',
    clean: true,
  },
  optimization: {
    runtimeChunk: 'single',
  },
  devServer: {
    historyApiFallback: true,
    hot: true,
    open: '/',
    port: 3000,
    proxy: [
      {
        context: (pathname) => {
          return (
            pathname !== '/' && ['/app', '/assets', '/web/auth'].every((prefix) => !pathname.startsWith(prefix))
          );
        },
        target: `http://localhost:${centralDogmaPort}`,
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

export default config;
