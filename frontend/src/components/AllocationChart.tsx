import {BarElement, CategoryScale, Chart as ChartJS, Legend, LinearScale, Tooltip, ChartOptions} from 'chart.js';
import {Bar} from 'react-chartjs-2';
import {PieChart} from 'lucide-react';
import {AssetSnapshot} from '@/types';
import {formatTooltipLabel, formatTickLabel} from '@/utils/chartFormatters';

ChartJS.register(CategoryScale, LinearScale, BarElement, Tooltip, Legend);

interface AllocationChartProps {
    assets: AssetSnapshot[] | Record<string, AssetSnapshot> | null | undefined;
}

const AllocationChart = ({ assets }: AllocationChartProps) => {
    if (!assets || Object.keys(assets).length === 0) {
        return (
            <div className="glass-panel flex flex-col items-center justify-center py-16 text-slate-500">
                <PieChart size={48} className="mb-4 opacity-20" />
                <h2 className="text-lg font-medium text-slate-300">Portfolio Allocation</h2>
                <p>No asset data available.</p>
            </div>
        );
    }

    const assetsArray = Array.isArray(assets) ? assets : Object.values(assets);
    const sortedAssets = assetsArray.sort((a, b) => b.valueUSD - a.valueUSD);
    const topAssets = sortedAssets.slice(0, 15);

    const chartLabels = topAssets.map(a => a.symbol);
    const chartValues = topAssets.map(a => a.valueUSD);

    const data = {
        labels: chartLabels,
        datasets: [
            {
                label: 'Value (USD)',
                data: chartValues,
                backgroundColor: 'rgba(56, 189, 248, 0.8)',
                hoverBackgroundColor: 'rgba(56, 189, 248, 1)',
                borderColor: 'rgba(56, 189, 248, 0.5)',
                borderWidth: 1,
                borderRadius: 4,
            },
        ],
    };

    const options: ChartOptions<'bar'> = {
        indexAxis: 'y' as const,
        maintainAspectRatio: false,
        plugins: {
            legend: {
                display: false,
            },
            tooltip: {
                backgroundColor: 'rgba(15, 23, 42, 0.9)',
                titleColor: '#cbd5e1',
                bodyColor: '#f8fafc',
                borderColor: 'rgba(51, 65, 85, 0.5)',
                borderWidth: 1,
                padding: 12,
                cornerRadius: 8,
                callbacks: {
                    /* v8 ignore next */
                    label: (context) => formatTooltipLabel({ raw: context.raw as number })
                }
            }
        },
        scales: {
            x: {
                grid: {
                    color: 'rgba(51, 65, 85, 0.3)',
                },
                border: {
                    display: false,
                },
                ticks: {
                    color: '#94a3b8',
                    /* v8 ignore next */
                    callback: (value: number | string) => formatTickLabel(value),
                    font: {
                        family: 'Inter',
                    }
                }
            },
            y: {
                grid: {
                    display: false,
                },
                border: {
                    display: false,
                },
                ticks: {
                    color: '#e2e8f0',
                    font: {
                        family: 'Inter',
                        weight: 'bold'
                    }
                }
            }
        }
    };

    return (
        <div className="glass-panel">
            <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wider mb-6 flex items-center gap-2">
                <PieChart size={18} className="text-blue-400" />
                Portfolio Allocation (Top Assets)
            </h2>
            <div className="relative h-[350px] w-full">
                <Bar data={data} options={options} />
            </div>
        </div>
    );
};

export default AllocationChart;
