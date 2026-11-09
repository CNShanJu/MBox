#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「鱼」加载动画里那些**逐帧抖动的图层**固定住(头部那圈白光就是它)。

问题(实测):
    `asset 1` 里的 `blur 2`(`Head glow`,头部那团白光)和 `blur`(身后的光)两条位移
    轨道各有 **151 个关键帧、每帧一个**,数值在 ±4.5 设计单位内来回跳、方向每隔 1~3 帧
    就翻一次 —— 这是 AE 里 `wiggle()` 之类表达式被 bodymovin **按 60fps 烘成关键帧**的
    结果。换算到屏幕上:±1dp = ±3px(3x 屏),**每秒 60 次**。因为是一圈亮光,任何 1px
    的位移都极显眼 —— 就是"头上那圈白的一直在抖,盯久了特别明显"。

    它跟运动曲线、跟蒙版裁切都无关,所以前两次改运动/改蒙版都没治好。

本脚本做什么:
    扫描所有图层的 p/s/r/o 轨道,挑出"关键帧 >= 30 且抖动度(二阶差分/一阶差分)> 0.6"
    的**逐帧噪声轨道**,把整条轨道换成**一个静止值**(序列均值)。这就是把该图层
    "固定在鱼头位置":位置不再有任何抖动,视觉效果只是那 2dp 的慢漂移消失。

    顺手会打印所有被判定为噪声的轨道,方便核对。

用法:
    python scripts/pin-loading-glow.py            # 就地固定
    python scripts/pin-loading-glow.py --check     # 只列出来
"""

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANIM_JSON = os.path.join(
    ROOT, "app", "src", "main", "assets", "loading", "glowing_fish_loader",
    "glowing_fish_loader.json")

MIN_KEYS = 30       # 逐帧烘焙出来的轨道通常 >= 30 个关键帧
JITTER_RATIO = 0.6  # 二阶差分之和 / 一阶差分之和,越大越像"逐帧噪声"
VIEW_W_DP = 112.0
COMP_W = 860.0


def series_of(prop):
    """取轨道的关键帧数值序列(多分量按第一分量判断)。"""
    vals = []
    for k in prop['k']:
        v = k.get('s')
        if isinstance(v, list) and v and isinstance(v[0], (int, float)):
            vals.append(float(v[0]))
        elif isinstance(v, (int, float)):
            vals.append(float(v))
    return vals


def jitter_ratio(vals):
    d1 = [vals[i + 1] - vals[i] for i in range(len(vals) - 1)]
    d2 = [d1[i + 1] - d1[i] for i in range(len(d1) - 1)]
    s1 = sum(abs(x) for x in d1)
    return (sum(abs(x) for x in d2) / s1) if s1 > 1e-9 else 0.0


def mean_value(prop):
    """按分量求均值,保持原有维度(如 [x,y,0])。"""
    first = prop['k'][0].get('s')
    if not isinstance(first, list):
        return sum(float(k.get('s')) for k in prop['k']) / len(prop['k'])
    dim = len(first)
    acc = [0.0] * dim
    n = 0
    for k in prop['k']:
        v = k.get('s')
        if not isinstance(v, list) or len(v) != dim:
            continue
        for j in range(dim):
            acc[j] += v[j]
        n += 1
    return [round(x / max(1, n), 3) for x in acc]


def iter_layers(comp):
    for l in comp['layers']:
        yield 'root', l
    for a in comp.get('assets', []):
        for l in a.get('layers', []):
            yield 'asset%s' % a['id'], l


def main():
    check_only = '--check' in sys.argv
    with open(ANIM_JSON, encoding='utf-8') as fp:
        comp = json.load(fp)

    hits = []
    for owner, l in iter_layers(comp):
        for prop_name in ('p', 's', 'r', 'o'):
            prop = l['ks'].get(prop_name)
            if not isinstance(prop, dict) or prop.get('a') != 1:
                continue
            if len(prop['k']) < MIN_KEYS:
                continue
            vals = series_of(prop)
            if len(vals) < MIN_KEYS:
                continue
            ratio = jitter_ratio(vals)
            if ratio >= JITTER_RATIO:
                hits.append((owner, l, prop_name, prop, ratio, min(vals), max(vals)))

    print('逐帧噪声轨道体检:%s' % os.path.relpath(ANIM_JSON, ROOT))
    if not hits:
        print('  没发现(可能已经处理过)。')
        return
    k = VIEW_W_DP / COMP_W
    for owner, l, prop_name, prop, ratio, lo, hi in hits:
        print('  %-7s %-10s %-2s  %3d 帧  抖动度 %.2f  位移范围 %.1f~%.1f 单位(约 %.1f dp)'
              % (owner, l.get('nm'), prop_name, len(prop['k']), ratio, lo, hi, (hi - lo) * k))

    if check_only:
        print('\n(--check 模式,未写文件)')
        return

    for owner, l, prop_name, prop, ratio, lo, hi in hits:
        frozen = mean_value(prop)
        prop['a'] = 0
        prop['k'] = frozen
        print('  固定 %s/%s.%s -> %s' % (owner, l.get('nm'), prop_name, frozen))

    with open(ANIM_JSON, 'w', encoding='utf-8', newline='') as fp:
        json.dump(comp, fp, separators=(',', ':'), ensure_ascii=False)
    print('\n已写入 %s(%d 字节)' % (os.path.relpath(ANIM_JSON, ROOT),
                                    os.path.getsize(ANIM_JSON)))


if __name__ == '__main__':
    main()
