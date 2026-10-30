#!/usr/bin/env python3
"""Measure a command's process tree without collecting arguments or environment values."""

import argparse
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import time


def processes():
    """Read only numeric Linux process counters; short-lived processes may be missed."""
    result = {}
    for folder in Path('/proc').glob('[0-9]*'):
        try:
            fields = (folder / 'stat').read_text().rsplit(')', 1)[1].split()
            result[int(folder.name)] = {
                'parent': int(fields[1]),
                'cpu': (int(fields[11]) + int(fields[12])) / os.sysconf('SC_CLK_TCK'),
                'threads': int(fields[17]),
                'started': int(fields[19]),
                'rss': max(0, int(fields[21])) * os.sysconf('SC_PAGE_SIZE'),
            }
        except (OSError, ValueError, IndexError):
            continue
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('command required after --')
    args.output.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    child = subprocess.Popen(command, start_new_session=True)
    def forward(signum, _frame):
        try:
            os.killpg(child.pid, signum)
        except ProcessLookupError:
            pass
    signal.signal(signal.SIGTERM, forward)
    signal.signal(signal.SIGINT, forward)
    peak_rss = peak_processes = peak_threads = 0
    cpu = {}
    supported = platform.system() == 'Linux'
    with (args.output / 'process-samples.tsv').open('w') as samples:
        samples.write('seconds\trss_bytes\tprocesses\tthreads\n')
        while child.poll() is None:
            if supported:
                current = processes()
                selected = {child.pid}
                while True:
                    updated = selected | {pid for pid, item in current.items() if item['parent'] in selected}
                    if updated == selected:
                        break
                    selected = updated
                tree = [(pid, item) for pid, item in current.items() if pid in selected]
                rss = sum(item['rss'] for _, item in tree)
                threads = sum(item['threads'] for _, item in tree)
                peak_rss = max(peak_rss, rss)
                peak_processes = max(peak_processes, len(tree))
                peak_threads = max(peak_threads, threads)
                for pid, item in tree:
                    cpu[(pid, item['started'])] = item['cpu']
                samples.write(f'{time.monotonic() - started:.3f}\t{rss}\t{len(tree)}\t{threads}\n')
            time.sleep(0.5)
    result = {
        'platform': platform.system(),
        'cpu_count': os.cpu_count(),
        'memory_total_bytes': (int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemTotal:'))) * 1024) if supported else None,
        'elapsed_seconds': round(time.monotonic() - started, 3),
        'exit_code': child.returncode,
        'sampling_interval_seconds': 0.5,
        'peak_tree_rss_bytes': peak_rss if supported else None,
        'sampled_tree_cpu_seconds': round(sum(cpu.values()), 3) if supported else None,
        'peak_processes': peak_processes if supported else None,
        'peak_threads': peak_threads if supported else None,
    }
    (args.output / 'resources.json').write_text(json.dumps(result, indent=2) + '\n')
    raise SystemExit(child.returncode if child.returncode >= 0 else 128 - child.returncode)


if __name__ == '__main__':
    main()
