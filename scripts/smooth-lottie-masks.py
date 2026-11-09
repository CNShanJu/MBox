#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「鱼」加载动画里**形状蒙版**的形变动画重采样成连续速度,消除每秒十几次的"顿"。

问题(实测,不是猜):
    glowing_fish_loader.json 的鱼不是"整条贴图在动",而是**用形状当 alpha 蒙版**把颜色
    素材裁成鱼形 —— `Fish Main`(头+身)、`Main_Tail`(尾)、`mask_fin`/`mask_fin 2`(鳍)
    这四条路径就是鱼的轮廓,`Fish_body` 等预合成层靠它们裁出鱼的形状。

    这四条轮廓的关键帧用的是 AE 的 Easy Ease(`o={0.167,0}` / `i={0.833,1}`),它的
    端点斜率正好是 **0**:每到一个关键帧,轮廓的形变速度就归零,再重新加速。
    `Fish Main` 有 31 个关键帧、每 5 帧一个 → **每秒停 12 次**;尾巴/鳍同理。
    因为轮廓同时是那圈浅色描边的裁切边界,看上去就是"头上那一圈白的一直在抖"。

    这也解释了为什么改 `Null 1` 的位移/旋转曲线没用(那只是把颜色素材在蒙版里挪动),
    甚至更糟:素材和轮廓的时序被改得不一样了,裁切边在素材上滑得更多。

本脚本做什么:
    对这五条动画路径逐帧重采样:每个顶点的 v/i/o 各坐标用保形 Hermite(不过冲、
    极值处才停)插值,再写回成 0..150 的逐帧关键帧。关键帧上的姿态与原来完全一致
    (锚点值不变、时间不变),只是帧与帧之间不再"停一下再走"。

用法:
    python scripts/smooth-lottie-masks.py            # 就地重采样
    python scripts/smooth-lottie-masks.py --check     # 只打印前后指标
    python scripts/smooth-lottie-masks.py --step 2    # 每 2 帧写一个关键帧(文件更小)
"""

import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANIM_JSON = os.path.join(
    ROOT, "app", "src", "main", "assets", "loading", "glowing_fish_loader",
    "glowing_fish_loader.json")

PERIOD = 150          # 一整圈 = 150 帧 = 2.5s @60fps
VIEW_W_DP = 112.0     # 视图边长,用于把设计单位换算成屏幕 dp
COMP_W = 860.0


# ---------------------------------------------------------------- 数学

def fc_tangents(ts, ys):
    """Fritsch–Carlson 保形切线:极值处为 0,区间内不过冲。"""
    n = len(ts)
    if n == 1:
        return [0.0]
    h = [ts[i + 1] - ts[i] for i in range(n - 1)]
    d = [(ys[i + 1] - ys[i]) / h[i] for i in range(n - 1)]
    m = [0.0] * n
    m[0] = d[0]
    m[-1] = d[-1]
    for i in range(1, n - 1):
        dp, dn = d[i - 1], d[i]
        if dp * dn <= 0:
            m[i] = 0.0
        else:
            hp, hc = h[i - 1], h[i]
            m[i] = 3.0 * (hp + hc) / ((2.0 * hc + hp) / dp + (hc + 2.0 * hp) / dn)
    return m


def hermite(ts, ys, ms, x):
    n = len(ts)
    if n == 1:
        return ys[0]
    if x <= ts[0]:
        return ys[0]
    if x >= ts[-1]:
        return ys[-1]
    i = 0
    while i < n - 2 and ts[i + 1] < x:
        i += 1
    h = ts[i + 1] - ts[i]
    u = (x - ts[i]) / h
    u2, u3 = u * u, u * u * u
    return ((2 * u3 - 3 * u2 + 1) * ys[i] + (u3 - 2 * u2 + u) * ms[i] * h
            + (-2 * u3 + 3 * u2) * ys[i + 1] + (u3 - u2) * ms[i + 1] * h)


def series_sampler(times, values):
    m = fc_tangents(times, values)
    return lambda x: hermite(times, values, m, x)


# ------------------------------------------------------- 路径关键帧处理

def subpath(kf):
    s = kf['s']
    return s[0] if isinstance(s, list) and s and isinstance(s[0], dict) else None


def bake_path(ks, period, step):
    """把一条动画路径重采样成逐帧关键帧。"""
    keys = [k for k in ks['k'] if k.get('t') is not None]
    keys.sort(key=lambda k: k['t'])
    base = subpath(keys[0])
    if base is None:
        return None
    closed = bool(base.get('c'))
    nv = len(base['v'])

    # 每个顶点的 v / i / o 各两个坐标,各建一条时间序列
    solvers = {'v': [], 'i': [], 'o': []}
    for chan in ('v', 'i', 'o'):
        for k in range(nv):
            for axis in (0, 1):
                ts, ys = [], []
                for kf in keys:
                    sp = subpath(kf)
                    if sp is None:
                        continue
                    arr = sp.get(chan) or []
                    pt = arr[k] if k < len(arr) else [0, 0]
                    ts.append(kf['t'])
                    ys.append(pt[axis])
                if len(ts) < 2 or all(abs(y - ys[0]) < 1e-9 for y in ys):
                    val = ys[0]
                    solvers[chan].append((k, axis, (lambda v: (lambda x: v))(val)))
                else:
                    solvers[chan].append((k, axis, series_sampler(ts, ys)))

    out = []
    frames = list(range(0, period + 1, max(1, step)))
    if frames[-1] != period:
        frames.append(period)
    for f in frames:
        v = [[0.0, 0.0] for _ in range(nv)]
        i_ = [[0.0, 0.0] for _ in range(nv)]
        o_ = [[0.0, 0.0] for _ in range(nv)]
        for chan, buf in (('v', v), ('i', i_), ('o', o_)):
            for (k, axis, fn) in solvers[chan]:
                buf[k][axis] = round(fn(f), 3)
        sp = {'i': i_, 'o': o_, 'v': v}
        if closed:
            sp['c'] = True
        out.append({'t': f, 's': [sp]})
    ks['k'] = out
    ks['a'] = 1
    return dict(name=None, verts=nv, keys=len(keys), closed=closed, frames=len(out))


def iter_animated_paths(comp):
    """产出所有形状动画项(含嵌套组)。"""
    def walk(items, owner):
        for it in items:
            if it.get('ty') == 'sh' and isinstance(it.get('ks'), dict) and it['ks'].get('a') == 1:
                yield owner, it
            if it.get('ty') == 'gr':
                yield from walk(it.get('it', []), owner)

    for l in comp['layers']:
        yield from walk(l.get('shapes') or [], 'root/%s' % l.get('nm'))
    for a in comp.get('assets', []):
        for l in a.get('layers', []):
            yield from walk(l.get('shapes') or [], 'asset%s/%s' % (a['id'], l.get('nm')))


def report_path(ks, label, period):
    """打印该路径一圈内的"停顿"与速度连续性。"""
    keys = sorted(ks['k'], key=lambda k: k['t'])
    solver_v = []
    base = subpath(keys[0])
    nv = len(base['v'])
    for k in range(nv):
        pair = []
        for axis in (0, 1):
            ts = [kf['t'] for kf in keys]
            ys = [kf['s'][0]['v'][k][axis] for kf in keys]
            pair.append(series_sampler(ts, ys) if len(ts) > 1 else (lambda v: (lambda x: v))(ys[0]))
        solver_v.append(pair)

    def at(f):
        return [(solver_v[k][0](f), solver_v[k][1](f)) for k in range(nv)]

    # 用原关键帧的缓动还原"改造前"的速度(只衡量顶点位移)
    def ease(u, o, i):
        if o is None or i is None:
            return u
        x1, y1 = o.get('x', 1 / 3), o.get('y', 0.0)
        x2, y2 = i.get('x', 2 / 3), i.get('y', 1.0)
        lo, hi = 0.0, 1.0
        for _ in range(40):
            m = (lo + hi) / 2
            xs = 3 * (1 - m) ** 2 * m * x1 + 3 * (1 - m) * m * m * x2 + m ** 3
            if xs < u:
                lo = m
            else:
                hi = m
        m = (lo + hi) / 2
        return 3 * (1 - m) ** 2 * m * y1 + 3 * (1 - m) * m * m * y2 + m ** 3

    def before(f):
        for idx in range(len(keys) - 1):
            k0, k1 = keys[idx], keys[idx + 1]
            if k0['t'] <= f <= k1['t']:
                span = k1['t'] - k0['t']
                u = 0 if span <= 0 else (f - k0['t']) / span
                e = ease(u, k0.get('o'), k0.get('i'))
                a = k0['s'][0]['v']
                b = k1['s'][0]['v']
                return [(a[j][0] + (b[j][0] - a[j][0]) * e,
                         a[j][1] + (b[j][1] - a[j][1]) * e) for j in range(nv)]
        return [(p[0], p[1]) for p in keys[-1]['s'][0]['v']]

    def profile(fn, steps):
        pts = [fn(f) for f in steps]
        v = []
        for idx in range(len(pts) - 1):
            v.append(max(math.dist(pts[idx][j], pts[idx + 1][j]) for j in range(nv)))
        return v

    k = VIEW_W_DP / COMP_W

    def velocity(fn, f):
        a, b = fn(f - 0.25), fn(f + 0.25)
        return max(math.dist(a[j], b[j]) for j in range(nv)) / 0.5

    anchor_ts = [kf['t'] for kf in keys if 1 < kf['t'] < period - 1]
    if not anchor_ts:
        print('  %-26s %2d 顶点 / %2d 关键帧  (圈内无独立关键帧)' % (label, nv, len(keys)))
        return 0.0
    old_peak = max(velocity(before, f) for f in [x * 0.5 for x in range(int(period * 2))])
    new_peak = max(velocity(at, f) for f in [x * 0.5 for x in range(int(period * 2))])
    old_stop = sum(1 for t in anchor_ts if old_peak > 1e-9 and velocity(before, t) < 0.2 * old_peak)
    new_stop = sum(1 for t in anchor_ts if new_peak > 1e-9 and velocity(at, t) < 0.2 * new_peak)
    # 单帧位移峰值(换算成屏幕 dp)
    print('  %-26s %2d顶点/%2d帧  关键帧处"停顿" %2d -> %2d 处  峰值位移 %.2f -> %.2f dp/帧'
          % (label, nv, len(keys), old_stop, new_stop,
             old_peak * k, new_peak * k))
    return old_peak * k


def anchor_error(old_keys, ks, period):
    """重采样后,在原关键帧时刻逐顶点比对 v 坐标。"""
    new = {round(k['t'], 3): k['s'][0]['v'] for k in ks['k']}
    worst = 0.0
    for k in old_keys:
        if abs(k['t'] - period) < 0.01:
            t = period
        else:
            t = None
            for nt in new:
                if abs(nt - k['t']) < 0.01:
                    t = nt
                    break
        if t is None:
            continue
        a = k['s'][0]['v']
        b = new[t]
        for j in range(min(len(a), len(b))):
            worst = max(worst, math.dist(a[j], b[j]))
    return worst


def main():
    check_only = '--check' in sys.argv
    step = 1
    if '--step' in sys.argv:
        step = int(sys.argv[sys.argv.index('--step') + 1])

    with open(ANIM_JSON, encoding='utf-8') as fp:
        comp = json.load(fp)
    period = int(comp['op'])

    print('鱼加载动画的**形状蒙版**体检:%s' % os.path.relpath(ANIM_JSON, ROOT))
    print('  一圈 %d 帧 / %.2fs,蒙版形变每秒停顿次数 = 关键帧数 / 时长' % (period, period / 60.0))

    min_peak = 0.15   # dp/帧:低于此值的路径(几乎不动)不值得重采样
    all_paths = list(iter_animated_paths(comp))
    print('\n改造前(每个关键帧速度归零):')
    targets = []
    for owner, item in all_paths:
        peak = report_path(item['ks'], '%s/%s' % (owner.split('/')[-1], item.get('nm')), period)
        if peak >= min_peak:
            targets.append((owner, item))
    skipped = [p for p in all_paths if p not in targets]
    for owner, item in skipped:
        print('  (跳过,形变幅度可忽略)%s/%s' % (owner, item.get('nm')))

    if check_only:
        print('\n(--check 模式,未写文件)')
        return

    orig = {}
    for owner, item in targets:
        orig[owner + '/' + str(item.get('nm'))] = json.loads(json.dumps(item['ks']['k']))

    print('\n重采样(保形 Hermite,逐 %d 帧):' % step)
    for owner, item in targets:
        info = bake_path(item['ks'], period, step)
        if info:
            print('  %-26s %s' % ('%s/%s' % (owner, item.get('nm')), info))
    comp['op'] = period

    print('\n锚点姿态校验(重采样前后,每个原关键帧时刻逐顶点最大误差):')
    for owner, item in targets:
        err = anchor_error(orig[owner + '/' + str(item.get('nm'))], item['ks'], period)
        print('  %-26s %.4f 设计单位' % ('%s/%s' % (owner.split('/')[-1], item.get('nm')), err))

    with open(ANIM_JSON, 'w', encoding='utf-8', newline='') as fp:
        json.dump(comp, fp, separators=(',', ':'), ensure_ascii=False)
    print('\n已写入 %s(%d 字节)' % (os.path.relpath(ANIM_JSON, ROOT),
                                    os.path.getsize(ANIM_JSON)))


if __name__ == '__main__':
    main()
