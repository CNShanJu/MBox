# -*- coding: utf-8 -*-
import re, sys
sys.stdout.reconfigure(encoding='utf-8')

SRC = r'D:\Code\Video\icon\图片加载失败.svg'
svg = open(SRC, encoding='utf-8').read()
paths = re.findall(r'<path\s+([^>]*?)\s*>', svg)
print('total paths:', len(paths))
for i, p in enumerate(paths):
    def g(attr):
        m = re.search(attr + r'="([^"]*)"', p)
        return m.group(1) if m else '?'
    d = g('d')
    print('%d | x=%s y=%s w=%s h=%s | fill=%s op=%s | d[:40]=%s' % (
        i, g('target-x'), g('target-y'), g('target-width'), g('target-height'),
        g('fill'), g('fill-opacity'), d[:40]))
