# -*- coding: utf-8 -*-
import re, sys
sys.stdout.reconfigure(encoding='utf-8')

SRC = r'D:\Code\Video\icon\mao-无背景.svg'
DST = r'D:\Code\Video\TVBoxOS-Mobile\app\src\main\res\drawable\iv_splash.xml'

svg = open(SRC, encoding='utf-8').read()
paths = re.findall(r'<path\s+([^>]*?)\s*>', svg)
print('path 数量:', len(paths))

filtered = []
for p in paths:
    d = re.search(r'd="([^"]*)"', p)
    if not d:
        continue
    fill = re.search(r'fill="([^"]*)"', p)
    fill_op = re.search(r'fill-opacity="([^"]*)"', p)
    if fill_op and float(fill_op.group(1)) == 0:
        print('跳过透明背景:', d.group(1)[:50])
        continue
    filtered.append((fill.group(1) if fill else '#000000', d.group(1)))

print('保留 path:', len(filtered))
out = ['<?xml version="1.0" encoding="utf-8"?>',
       '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
       '    android:width="360dp"',
       '    android:height="360dp"',
       '    android:viewportWidth="1600"',
       '    android:viewportHeight="1600">']
for fill, dd in filtered:
    out.append('    <path android:fillColor="%s" android:pathData="%s"/>' % (fill, dd))
out.append('</vector>')
open(DST, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('saved', DST)
