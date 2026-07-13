// Remove the source-map-loader pre-rule completely to prevent warnings.
// This preserves debugging sourcemaps generated natively by Webpack (via devtool: 'source-map')
// while eliminating all external source-map-loader resolution warnings.
if (config.module && config.module.rules) {
    config.module.rules = config.module.rules.filter(rule => {
        if (rule.use && Array.isArray(rule.use)) {
            return !rule.use.some(loader => typeof loader === 'string' && loader.includes("source-map-loader"));
        }
        return true;
    });
}
