// Disable source map generation and source-map-loader warnings in production Webpack compilation
config.devtool = false;
if (config.module && config.module.rules) {
    config.module.rules = config.module.rules.filter(rule => {
        if (rule.use && Array.isArray(rule.use)) {
            return !rule.use.some(loader => typeof loader === 'string' && loader.includes("source-map-loader"));
        }
        return true;
    });
}
