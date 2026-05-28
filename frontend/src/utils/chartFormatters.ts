export const formatTooltipLabel = (context: { raw: number }) =>
    new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(context.raw);

export const formatTickLabel = (value: number | string) => '$' + value;
