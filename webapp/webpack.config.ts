import path from 'path';
import webpack from 'webpack';
import 'webpack-dev-server';
import HtmlWebpackPlugin from 'html-webpack-plugin';

const isDev = !!process.env.WEBPACK_DEV;

const config: webpack.Configuration = {
    mode: isDev ? 'development' : 'production',
    devtool: isDev ? 'inline-source-map' : undefined,
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
        extensions: ['.tsx', '.ts', '.js'],
    },
    plugins: [
        new HtmlWebpackPlugin({
            template: './src/index.html',
            hash: true,
        }),
    ],
    output: {
        filename: '[name].js',
        path: path.resolve(__dirname, './build/web'),
        chunkFilename: '[id].[chunkhash].js',
        clean: true,
    },
    optimization: {
        runtimeChunk: 'single',
    },
    devServer: {
        static: './dist',
        client: {
            overlay: {
                warnings: false,
                errors: true,
            },
        },
    },
};

export default config;
