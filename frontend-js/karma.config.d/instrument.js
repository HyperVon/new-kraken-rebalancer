config.webpack.devtool = 'inline-source-map';

config.webpack.module.rules.push({
    test: /kraken-bot-frontend-js\.js$/,
    use: {
        loader: 'istanbul-instrumenter-loader',
        options: { esModules: true, produceSourceMap: true }
    },
    enforce: 'post',
    exclude: /node_modules/
});
