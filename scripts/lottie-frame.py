#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Lottie 的某一帧渲染成 SVG(用于离线核对素材,不参与打包)。

只覆盖本动画用到的特性:图层变换/父子、形状路径(含贝塞尔手柄)、纯色与渐变填充、
纯色与渐变描边、图层不透明度、alpha 蒙版(轨道遮罩)。模糊等特效不还原。

用法:
    python scripts/lottie-frame.py <json> <frame> <宽度px> [out.svg]
"""

import json
import math
import sys


def lerp(a, b, t):
    return a + (b - a) * t


def _f(v, default):
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, list) and v and isinstance(v[0], (int, float)):
        return float(v[0])
    return default


def ease(o, i, u):
    if o is None or i is None:
        return u
    x1, y1 = _f(o.get('x'), 1 / 3), _f(o.get('y'), 0.0)
    x2, y2 = _f(i.get('x'), 2 / 3), _f(i.get('y'), 1.0)
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


def lerp_shape(a, b, e):
    out = {'c': a.get('c', False)}
    for chan in ('v', 'i', 'o'):
        pa, pb = a.get(chan) or [], b.get(chan) or []
        n = min(len(pa), len(pb))
        out[chan] = [[lerp(pa[j][0], pb[j][0], e), lerp(pa[j][1], pb[j][1], e)] for j in range(n)]
    return out


def num(v, default=0.0):
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, dict):
        k = v.get('k')
        if isinstance(k, (int, float)):
            return float(k)
    return default


def val(prop, frame, dim=None):
    """求值一个可动画属性,返回 list(向量)或 float。"""
    if prop is None:
        return None
    if not isinstance(prop, dict):
        return prop
    if prop.get('a') != 1:
        k = prop.get('k')
        return k
    keys = prop['k']
    if frame <= keys[0]['t']:
        return keys[0]['s']
    for idx in range(len(keys) - 1):
        k0, k1 = keys[idx], keys[idx + 1]
        if k0['t'] <= frame <= k1['t']:
            span = k1['t'] - k0['t']
            u = 0 if span <= 0 else (frame - k0['t']) / span
            e = ease(k0.get('o'), k0.get('i'), u)
            v0, v1 = k0['s'], k1['s']
            if isinstance(v0, list) and v0 and isinstance(v0[0], dict):
                return [lerp_shape(a, b, e) for a, b in zip(v0, v1)]
            if isinstance(v0, list) and isinstance(v1, list):
                return [lerp(v0[j], v1[j], e) for j in range(min(len(v0), len(v1)))]
            return lerp(v0, v1, e)
    return keys[-1]['s']


def path_of(item, frame):
    """形状路径 -> SVG d。"""
    ks = item.get('ks', {})
    v = val(ks, frame)
    if isinstance(v, list) and v and isinstance(v[0], dict):
        v = v[0]
    if not isinstance(v, dict):
        return '', False
    pts = v.get('v', [])
    ins = v.get('i', [])
    outs = v.get('o', [])
    closed = bool(v.get('c'))
    if not pts:
        return '', closed
    d = ['M %.3f %.3f' % (pts[0][0], pts[0][1])]
    n = len(pts)
    rng = range(n) if closed else range(n - 1)
    for k in rng:
        a, b = k, (k + 1) % n
        o1 = outs[k] if k < len(outs) else [0, 0]
        i2 = ins[b] if b < len(ins) else [0, 0]
        d.append('C %.3f %.3f %.3f %.3f %.3f %.3f' % (
            pts[a][0] + o1[0], pts[a][1] + o1[1],
            pts[b][0] + i2[0], pts[b][1] + i2[1],
            pts[b][0], pts[b][1]))
    if closed:
        d.append('Z')
    return ' '.join(d), closed


def hexc(c, alpha=1.0):
    r, g, b = c[0], c[1], c[2]
    if alpha >= 1.0:
        return '#%02x%02x%02x' % (max(0, min(255, round(r * 255))),
                                  max(0, min(255, round(g * 255))),
                                  max(0, min(255, round(b * 255))))
    return 'rgba(%d,%d,%d,%.3f)' % (round(r * 255), round(g * 255), round(b * 255), alpha)


def stops_of(g, frame, gid):
    k = val(g.get('k'), frame)
    if isinstance(k, dict):
        k = k.get('k')
    if not isinstance(k, list):
        return '', []
    out = []
    stops = []
    for idx in range(0, len(k) - 3, 4):
        off, r, gg, b = k[idx], k[idx + 1], k[idx + 2], k[idx + 3]
        stops.append('<stop offset="%.4f" stop-color="%s"/>' % (max(0, min(1, off)), hexc([r, gg, b])))
    return '', stops


def gradient_def(g, frame, gid):
    k = val(g.get('k'), frame)
    if isinstance(k, dict):
        k = k.get('k')
    if not isinstance(k, list):
        return None
    s = val(g.get('s'), frame) or [0, 0]
    e = val(g.get('e'), frame) or [100, 0]
    typ = g.get('t', 1)
    stops = []
    for idx in range(0, len(k) - 3, 4):
        off, r, gg, b = k[idx], k[idx + 1], k[idx + 2], k[idx + 3]
        stops.append('<stop offset="%.4f" stop-color="%s"/>' % (max(0, min(1, off)), hexc([r, gg, b])))
    if typ == 2:
        r = math.dist(s, e)
        return ('<radialGradient id="%s" gradientUnits="userSpaceOnUse" cx="%.3f" cy="%.3f" r="%.3f">'
                '%s</radialGradient>' % (gid, s[0], s[1], r, ''.join(stops)))
    return ('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="%.3f" y1="%.3f" x2="%.3f" y2="%.3f">'
            '%s</linearGradient>' % (gid, s[0], s[1], e[0], e[1], ''.join(stops)))


class Renderer:
    def __init__(self, comp, frame, width):
        self.comp = comp
        self.assets = {a['id']: a for a in comp.get('assets', [])}
        self.frame = frame
        self.scale = width / float(comp['w'])
        self.defs = []
        self.uid = 0

    def nid(self, p='g'):
        self.uid += 1
        return '%s%d' % (p, self.uid)

    def layer_transform(self, l, siblings=None):
        """合成「祖先 -> 自身」的变换链(Lottie 的 parent 是按 ind 引用的)。"""
        chain = []
        cur = l
        guard = 0
        while cur is not None and guard < 16:
            chain.append(cur)
            pid = cur.get('parent')
            cur = siblings.get(pid) if (pid is not None and siblings) else None
            guard += 1
        chain.reverse()
        parts = []
        for item in chain:
            ks = item['ks']
            p = val(ks['p'], self.frame) or [0, 0]
            s = val(ks['s'], self.frame) or [100, 100]
            r = num(val(ks['r'], self.frame), 0.0)
            a = val(ks['a'], self.frame) or [0, 0]
            parts.append('translate(%.3f %.3f) rotate(%.3f) scale(%.4f %.4f) translate(%.3f %.3f)' % (
                p[0], p[1], r, s[0] / 100.0, (s[1] if len(s) > 1 else s[0]) / 100.0, -a[0], -a[1]))
        return ' '.join(parts)

    def shapes_svg(self, items, frame, mono=False):
        """返回 (defs, body)；body 里已按顺序画好 fill/stroke。"""
        defs = []

        def walk(items):
            body = []
            paths = []
            fills = []
            strokes = []
            groups = []
            for it in items:
                ty = it.get('ty')
                if ty == 'sh':
                    paths.append(it)
                elif ty in ('fl', 'gf'):
                    fills.append(it)
                elif ty in ('st', 'gs'):
                    strokes.append(it)
                elif ty == 'gr':
                    groups.append(it)
            for sh in paths:
                d, closed = path_of(sh, frame)
                if not d:
                    continue
                for f in fills:
                    if f['ty'] == 'fl':
                        c = val(f.get('c'), frame)
                        op = num(val(f.get('o'), frame), 100.0) / 100.0
                        body.append('<path d="%s" fill="%s" opacity="%.3f"/>'
                                    % (d, '#ffffff' if mono else hexc(c, 1.0), op))
                    else:
                        gid = self.nid('grad')
                        gd = gradient_def(f.get('g'), frame, gid)
                        if gd:
                            defs.append(gd)
                            op = num(val(f.get('o'), frame), 100.0) / 100.0
                            body.append('<path d="%s" fill="url(#%s)" opacity="%.3f"/>' % (d, gid, op))
                for s in strokes:
                    w = num(val(s.get('w'), frame), 1.0)
                    op = num(val(s.get('o'), frame), 100.0) / 100.0
                    if s['ty'] == 'st':
                        c = val(s.get('c'), frame)
                        body.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.3f" '
                                    'opacity="%.3f" stroke-linecap="round" stroke-linejoin="round"/>'
                                    % (d, '#ffffff' if mono else hexc(c, 1.0), w, op))
                    else:
                        gid = self.nid('grad')
                        gd = gradient_def(s.get('g'), frame, gid)
                        if gd:
                            defs.append(gd)
                            body.append('<path d="%s" fill="none" stroke="url(#%s)" stroke-width="%.3f" '
                                        'opacity="%.3f" stroke-linecap="round" stroke-linejoin="round"/>'
                                        % (d, gid, w, op))
            for g in groups:
                tr = g.get('tr')
                inner_tr = ''
                if tr:
                    p = val(tr.get('p'), frame) or [0, 0]
                    sc = val(tr.get('s'), frame) or [100, 100]
                    rr = num(val(tr.get('r'), frame), 0.0)
                    aa = val(tr.get('a'), frame) or [0, 0]
                    inner_tr = 'translate(%.3f %.3f) rotate(%.3f) scale(%.4f %.4f) translate(%.3f %.3f)' % (
                        p[0], p[1], rr, sc[0] / 100.0, (sc[1] if len(sc) > 1 else sc[0]) / 100.0, -aa[0], -aa[1])
                sub = walk(g.get('it', []))
                if inner_tr:
                    body.append('<g transform="%s">%s</g>' % (inner_tr, sub))
                else:
                    body.append(sub)
            return ''.join(body)

        return defs, walk(items)

    def render_layers(self, layers):
        """返回 (defs, body)。处理 tt 轨道遮罩(Lottie: 被遮罩层取它上面那一层)。"""
        siblings = {l['ind']: l for l in layers if 'ind' in l}
        defs = []
        out = []
        i = 0
        while i < len(layers):
            l = layers[i]
            if l.get('tt'):
                matte = layers[i - 1] if i > 0 else None
                content = self.render_single(l, siblings)
                if matte is not None:
                    cid = self.nid('clip')
                    defs.append('<clipPath id="%s" clipPathUnits="userSpaceOnUse">%s</clipPath>'
                                % (cid, self._matte_svg(matte, siblings)))
                    out.append('<g clip-path="url(#%s)">%s</g>' % (cid, content))
                else:
                    out.append(content)
                i += 1
                continue
            if i + 1 < len(layers) and layers[i + 1].get('tt'):
                i += 1  # 已被下一层当遮罩消费
                continue
            out.append(self.render_single(l, siblings))
            i += 1
        return defs, ''.join(out)

    def _matte_svg(self, l, siblings):
        """把遮罩层的形状拍平成一组 <path transform=...>,clipPath 只认形状元素。"""
        tr = self.layer_transform(l, siblings)

        def flat_transform(group):
            if not group:
                return ''
            p = val(group.get('p'), self.frame) or [0, 0]
            s = val(group.get('s'), self.frame) or [100, 100]
            r = num(val(group.get('r'), self.frame), 0.0)
            a = val(group.get('a'), self.frame) or [0, 0]
            return ' translate(%.3f %.3f) rotate(%.3f) scale(%.4f %.4f) translate(%.3f %.3f)' % (
                p[0], p[1], r, s[0] / 100.0, (s[1] if len(s) > 1 else s[0]) / 100.0, -a[0], -a[1])

        out = []

        def walk(items, acc=''):
            for it in items:
                if it.get('ty') == 'sh':
                    d, _ = path_of(it, self.frame)
                    if d:
                        out.append('<path d="%s" transform="%s%s"/>' % (d, tr, acc))
                elif it.get('ty') == 'gr':
                    walk(it.get('it', []), acc + flat_transform(it.get('tr')))

        walk(l.get('shapes') or [])
        return ''.join(out)

    def render_single(self, l, siblings=None):
        if not (l.get('ip', 0) <= self.frame < l.get('op', 10 ** 9)):
            return ''
        tr = self.layer_transform(l, siblings)
        op = num(val(l['ks'].get('o'), self.frame), 100.0) / 100.0
        if l.get('ty') == 0 and l.get('refId'):
            asset = self.assets.get(l['refId'])
            if not asset:
                return ''
            d, b = self.render_layers(asset.get('layers', []))
            self.defs.extend(d)
            return '<g transform="%s" opacity="%.3f">%s</g>' % (tr, op, b)
        d, b = self.shapes_svg(l.get('shapes') or [], self.frame)
        self.defs.extend(d)
        if not b:
            return ''
        return '<g transform="%s" opacity="%.3f">%s</g>' % (tr, op, b)


def main():
    js, frame, width = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
    out = sys.argv[4] if len(sys.argv) > 4 else 'frame.svg'
    comp = json.load(open(js, encoding='utf-8'))
    r = Renderer(comp, frame, width)
    d, b = r.render_layers(comp['layers'])
    all_defs = r.defs + d
    w = width
    h = width * comp['h'] / float(comp['w'])
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="%.0f" height="%.0f" viewBox="0 0 %.0f %.0f">'
           % (w, h, comp['w'], comp['h']),
           '<defs>%s</defs>' % ''.join(all_defs),
           '<rect width="%d" height="%d" fill="#111111"/>' % (comp['w'], comp['h']),
           b, '</svg>']
    open(out, 'w', encoding='utf-8').write('\n'.join(svg))
    print('wrote %s (%.0fx%.0f)' % (out, w, h))


if __name__ == '__main__':
    main()
