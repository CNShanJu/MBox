# -*- coding: utf-8 -*-
import sys
sys.stdout.reconfigure(encoding='utf-8')
from PIL import Image
import vtracer

SRC = r'D:\Code\Video\icon\图片加载失败.png'
DST = r'D:\Code\Video\icon\图片加载失败-无水印.svg'

# 缩小到 1024 再矢量化,平衡清晰度与 SVG 体积
im = Image.open(SRC).convert('RGBA')
im = im.resize((1024, 1024), Image.LANCZOS)
tmp = r'D:\Code\Video\icon\_tmp_png2svg.png'
im.save(tmp, 'PNG')

vtracer.convert_image_to_svg_py(
    tmp, DST,
    colormode='color',   # 彩色
    filter_speckle=4,    # 滤掉小噪点
    color_precision=6,   # 颜色精度(越大颜色越少、体积越小)
    corner_threshold=60,
    length_threshold=4.0,
    splice_threshold=45,
    path_precision=2,
    mode='spline'        # 平滑曲线
)
import os
print('saved', DST, os.path.getsize(DST), 'bytes')
