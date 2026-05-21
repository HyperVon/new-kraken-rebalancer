import React from 'react';
import { Chart as ChartJS, CategoryScale, LinearScale, BarElement, Tooltip, Legend } from 'chart.js';
import { Bar } from 'react-chartjs-2';
import { PieChart } from 'lucide-react';
import { AssetSnapshot } from '@/types';

ChartJS.register(CategoryScale, LinearScale, BarElement, Tooltip, Legend);

interface AllocationChartProps {
    assets: AssetSnapshot[] | { [key: string]: AssetSnapshot } | undefined;
}

export const formatTooltipLabel = (context: { raw: number }) =>
    new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(context.raw);

export const formatTickLabel = (value: number | string) => '$' + value;

const AllocationChart: React.FC<AllocationChartProps> = ({ assets }) => {
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

    const chartLabels = topAssets.map(a => a.asset || (a as any).symbol);
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

    const options = {
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
                    label: (context: any) => formatTooltipLabel(context)
                }
            }
        },
        scales: {
            x: {
                grid: {
                    color: 'rgba(51, 65, 85, 0.3)',
                    drawBorder: false,
                },
                ticks: {
                    color: '#94a3b8',
                    /* v8 ignore next */
                    callback: (value: any) => formatTickLabel(value),
                    font: {
                        family: 'Inter',
                    }
                }
            },
            y: {
                grid: {
                    display: false,
                    drawBorder: false,
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
