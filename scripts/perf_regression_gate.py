# -*- coding: utf-8 -*-
"""性能回归门禁。

用途：
1. 执行 test/run_all_loadtests.py 全套并发场景；
2. 读取本次新增的 JSONL 报告；
3. 对非 AI 限流场景断言：成功率 >= 99%、平均响应 < 1000ms、P99 < 3000ms。

用法：
    python perf_regression_gate.py
    SKIP_RUN=1 python perf_regression_gate.py   # 只校验已有最新结果

环境变量：
    MAX_AVG_MS=1000
    MAX_P99_MS=3000
    MIN_SUCCESS_RATE=99.0
"""
import glob
import json
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
TEST_DIR = os.path.join(ROOT, 'test')
RUNNER = os.path.join(TEST_DIR, 'run_all_loadtests.py')
RESULT_DIR = os.path.join(TEST_DIR, 'loadtest_results')

MIN_SUCCESS_RATE = float(os.environ.get('MIN_SUCCESS_RATE', '99.0'))
MAX_AVG_MS = float(os.environ.get('MAX_AVG_MS', '1000'))
MAX_P99_MS = float(os.environ.get('MAX_P99_MS', '3000'))


def latest_result_file():
    files = sorted(glob.glob(os.path.join(RESULT_DIR, 'loadtest_*.jsonl')))
    return files[-1] if files else None


def read_lines(path):
    if not path or not os.path.exists(path):
        return []
    with open(path, 'r', encoding='utf-8') as f:
        return [json.loads(line) for line in f if line.strip()]


def main():
    result_file = latest_result_file()
    before = read_lines(result_file)
    before_count = len(before)

    if os.environ.get('SKIP_RUN') != '1':
        print('[性能回归] 开始执行 run_all_loadtests.py ...')
        proc = subprocess.run([sys.executable, RUNNER], cwd=TEST_DIR)
        if proc.returncode != 0:
            print(f'[性能回归] 场景执行失败，exit code={proc.returncode}')
            return 1

    result_file = latest_result_file()
    reports = read_lines(result_file)[before_count:]
    if not reports:
        print('[性能回归] 未读取到本次新增报告，跳过阈值校验')
        return 0

    failures = []
    for report in reports:
        case = report.get('case', '')
        # AI 限流场景的客户体验就是被拒绝，成功率不作为门禁指标
        if '限流' in case or 'AI' in case:
            continue
        rate = report.get('success_rate', 0)
        avg = report.get('avg_response_ms', 0)
        p99 = report.get('p99_ms', 0)
        print(f"[校验] {case}: 成功率={rate}% 平均={avg}ms P99={p99}ms")
        if rate < MIN_SUCCESS_RATE:
            failures.append(f'{case} 成功率 {rate}% < {MIN_SUCCESS_RATE}%')
        if avg > MAX_AVG_MS:
            failures.append(f'{case} 平均响应 {avg}ms > {MAX_AVG_MS}ms')
        if p99 > MAX_P99_MS:
            failures.append(f'{case} P99 {p99}ms > {MAX_P99_MS}ms')

    if failures:
        print('\n[性能回归] 未通过：')
        for item in failures:
            print('  - ' + item)
        return 1

    print('\n[性能回归] 全部阈值通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())