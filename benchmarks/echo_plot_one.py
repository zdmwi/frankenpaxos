from . import plot_latency_and_throughput
from . import util
import argparse
import matplotlib.pyplot as plt
import numpy as np
import os
import pandas as pd

def plot_throughput( ax: plt.Axes,
                    df: pd.DataFrame,
                    p_df: pd.DataFrame) -> None:
    # Plot throughput.
    ax.plot_date(df.index,
                 util.throughput(df, 250),
                 label='250ms',
                 fmt='-',
                 alpha=0.5)
    ax.plot_date(df.index,
                 util.throughput(df, 500),
                 label='500ms',
                 fmt='-',
                 alpha=0.7)
    ax.plot_date(df.index,
                 util.throughput(df, 1000),
                 label='1s',
                 fmt='-')
    if p_df is not None:
        prometheus_throughput = util.rate(p_df['echo_requests_total'], 1000)
        ax.plot_date(prometheus_throughput.index,
                     prometheus_throughput,
                     label='1s (Prometheus)',
                     fmt='--')
    ax.set_title('Throughput')
    ax.set_xlabel('Time')
    ax.set_ylabel('Throughput')

def main(args) -> None:
    # Read in data.
    df = pd.read_csv(args.data_csv, parse_dates=['start', 'stop'])
    df.index = df['start']
    df = df.sort_index(0)

    if args.prometheus_data:
        p_df = pd.read_csv(args.prometheus_data, index_col=[0], parse_dates=[0])
        p_df = p_df.sort_index(0)
    else:
        p_df = None

    # Drop first bit of data.
    start_time = df['start'].iloc[0]
    new_start_time = start_time + pd.DateOffset(seconds=args.drop)
    df = df[df['start'] >= new_start_time]
    if p_df is not None:
        start_time = p_df.index[0]
        new_start_time = start_time + pd.DateOffset(seconds=args.drop)
        p_df = p_df[p_df.index >= new_start_time]


    # See [1] for figure size defaults.
    #
    # [1]: https://matplotlib.org/api/_as_gen/matplotlib.pyplot.figure.html
    num_plots = 2
    fig, ax = plt.subplots(num_plots, 1, figsize=(6.4, num_plots * 4.8))

    latency_ms = df['latency_nanos'] / 1e6
    latency_ms = latency_ms[~util.outliers(latency_ms, args.stds)]
    plot_latency_and_throughput.plot_latency(ax[0], latency_ms)

    plot_throughput(ax[1], df, p_df)

    for axes in ax:
        axes.grid()
        axes.legend(loc='best')
        for label in axes.get_xticklabels():
            label.set_ha('right')
            label.set_rotation(20)
    fig.set_tight_layout(True)
    fig.savefig(args.output)
    print(f'Wrote plot to {args.output}.')

def get_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        'data_csv',
        type=str,
        help='Single benchmark CSV file (e.g., data.csv)',
    )
    parser.add_argument(
        '-p', '--prometheus_data',
        type=str,
        help='Prometheus benchmark CSV file (e.g., prometheus_data.csv)',
    )
    parser.add_argument(
        '-d', '--drop',
        type=float,
        default=0,
        help='Drop this number of seconds from the beginning of the benchmark',
    )
    parser.add_argument(
        '-s', '--stds',
        type=float,
        default=1e20,
        help='Latencies that deviate by more than <stds> stds are stripped',
    )
    parser.add_argument(
        '-o', '--output',
        type=str,
        default='echo_one.pdf',
        help='Output filename',
    )
    return parser

if __name__ == '__main__':
    from pandas.plotting import register_matplotlib_converters
    register_matplotlib_converters()
    main(get_parser().parse_args())