import React from 'react';
import { Chart as ChartJS, CategoryScale, LinearScale, BarElement, Tooltip, Legend } from 'chart.js';
import { Bar } from 'react-chartjs-2';

ChartJS.register(CategoryScale, LinearScale, BarElement, Tooltip, Legend);

const AllocationChart = ({ assets }) => {
    if (!assets || Object.keys(assets).length === 0) return <div>No data</div>;

    const sortedAssets = Object.values(assets).sort((a, b) => b.valueUSD - a.valueUSD);
    const topAssets = sortedAssets.slice(0, 15); // Show top 15
    // No "Others" for Bar chart usually, just show the biggest movers clearly

    const chartLabels = topAssets.map(a => a.symbol);
    const chartValues = topAssets.map(a => a.valueUSD);

    const data = {
        labels: chartLabels,
        datasets: [
            {
                label: 'Value (USD)',
                data: chartValues,
                backgroundColor: '#38bdf8', // Single color or gradient? Single clean color.
                borderColor: '#1e293b',
                borderWidth: 1,
                borderRadius: 4,
            },
        ],
    };

    const options = {
        indexAxis: 'y', // Horizontal bars
        maintainAspectRatio: false,
        plugins: {
            legend: {
                display: false, // We know what the bars are (Value)
            },
            tooltip: {
                callbacks: {
                    label: (context) => {
                        return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(context.raw);
                    }
                }
            }
        },
        scales: {
            x: {
                grid: {
                    color: '#334155'
                },
                ticks: {
                    color: '#e2e8f0',
                    callback: (value) => '$' + value
                }
            },
            y: {
                grid: {
                    display: false
                },
                ticks: {
                    color: '#f8fafc',
                    font: {
                        family: 'Inter',
                        weight: 'bold'
                    }
                }
            }
        }
    };

    return (
        <div className="card">
            <h2>Portfolio Allocation (Top Assets)</h2>
            <div style={{ height: '400px', position: 'relative' }}>
                <Bar data={data} options={options} />
            </div>
        </div>
    );
};

export default AllocationChart;
