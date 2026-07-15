config.reporters.push('coverage');
config.plugins.push('karma-coverage');
config.coverageReporter = {
    reporters: [
        { type: 'html', subdir: 'html' },
        { type: 'text-summary' },
        { type: 'json-summary', subdir: '.', file: 'coverage-summary.json' }
    ],
    check: {
        global: {
            statements: 90,
            branches: 75,
            functions: 90,
            lines: 90
        }
    }
};
