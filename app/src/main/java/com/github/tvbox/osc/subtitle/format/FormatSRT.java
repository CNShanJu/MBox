/**
 * Class that represents the .ASS and .SSA subtitle file format
 *
 * <br><br>
 * Copyright (c) 2012 J. David Requejo <br>
 * j[dot]david[dot]requejo[at] Gmail
 * <br><br>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 * <br><br>
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 * <br><br>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *
 * @author J. David REQUEJO
 *
 */

package com.github.tvbox.osc.subtitle.format;


import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.model.Time;
import com.github.tvbox.osc.subtitle.model.TimedTextObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class FormatSRT implements TimedTextFileFormat {

    /** 时间行匹配:HH:MM:SS,mmm --> HH:MM:SS,mmm;毫秒分隔符兼容 ,/. 与可选空格 */
    private static final Pattern TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.]\\s*(\\d{1,3})\\s*--\\s*>\\s*"
                    + "(\\d{1,2}):(\\d{2}):(\\d{2})[,.]\\s*(\\d{1,3})");

    public TimedTextObject parseFile(String fileName, InputStream is) throws IOException {

        TimedTextObject tto = new TimedTextObject();
        tto.fileName = fileName;

        BufferedReader br = new BufferedReader(new InputStreamReader(is));

        try {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // 索引行通常是纯数字,也可能缺失/非连续 —— 直接跳过,不强制顺序(旧实现用严格递增校验,
                // 一旦某条索引不连续就把后续条目全部丢弃,导致整片只解析出前几条)。
                if (isNumeric(line)) {
                    // 跳过索引行,读下一非空行作为时间行
                    String next;
                    while ((next = br.readLine()) != null) {
                        next = next.trim();
                        if (!next.isEmpty()) break;
                    }
                    line = next;
                }
                if (line == null || line.isEmpty()) continue;

                long[] t = parseTimeLine(line);
                if (t == null) continue; // 时间行不合法,跳过该条继续

                // 读取该条文本(直到空行)
                StringBuilder text = new StringBuilder();
                String textLine;
                while ((textLine = br.readLine()) != null) {
                    if (textLine.trim().isEmpty()) break;
                    text.append(textLine.trim()).append("<br />");
                }

                Subtitle caption = new Subtitle();
                caption.start = new Time("hh:mm:ss,ms", formatTime(t[0]));
                caption.end = new Time("hh:mm:ss,ms", formatTime(t[1]));
                caption.content = text.toString();

                int key = caption.start.mseconds;
                while (tto.captions.containsKey(key)) key++;
                tto.captions.put(key, caption);
            }
        } catch (Exception e) {
            // 非致命:保留已解析条目
        } finally {
            is.close();
        }

        tto.built = true;
        return tto;
    }

    /** 解析 "HH:MM:SS,mmm --> HH:MM:SS,mmm" 为 [startMs, endMs];不合法返回 null */
    private static long[] parseTimeLine(String line) {
        Matcher m = TIME.matcher(line);
        if (!m.matches()) return null;
        try {
            long start = toMs(m.group(1), m.group(2), m.group(3), m.group(4));
            long end = toMs(m.group(5), m.group(6), m.group(7), m.group(8));
            return new long[]{start, end};
        } catch (Throwable th) {
            return null;
        }
    }

    private static long toMs(String h, String m, String s, String ms) {
        return Long.parseLong(h) * 3600000 + Long.parseLong(m) * 60000
                + Long.parseLong(s) * 1000 + Long.parseLong(ms);
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /** ms 转 "HH:MM:SS,mmm" 供 Time 解析 */
    private static String formatTime(long ms) {
        long h = ms / 3600000;
        long m = (ms / 60000) % 60;
        long s = (ms / 1000) % 60;
        long milli = ms % 1000;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, milli);
    }

    public String[] toFile(TimedTextObject tto) {

        //first we check if the TimedTextObject had been built, otherwise...
        if (!tto.built)
            return null;

        //we will write the lines in an ArrayList,
        int index = 0;
        //the minimum size of the file is 4*number of captions, so we'll take some extra space.
        ArrayList<String> file = new ArrayList<String>(5 * tto.captions.size());
        //we iterate over our captions collection, they are ordered since they come from a TreeMap
        Collection<Subtitle> c = tto.captions.values();
        Iterator<Subtitle> itr = c.iterator();
        int captionNumber = 1;

        while (itr.hasNext()) {
            //new caption
            Subtitle current = itr.next();
            //number is written
            file.add(index++, "" + captionNumber++);
            //we check for offset value:
            if (tto.offset != 0) {
                current.start.mseconds += tto.offset;
                current.end.mseconds += tto.offset;
            }
            //time is written
            file.add(index++, current.start.getTime("hh:mm:ss,ms") + " --> " + current.end.getTime("hh:mm:ss,ms"));
            //offset is undone
            if (tto.offset != 0) {
                current.start.mseconds -= tto.offset;
                current.end.mseconds -= tto.offset;
            }
            //text is added
            String[] lines = cleanTextForSRT(current);
            int i = 0;
            while (i < lines.length)
                file.add(index++, "" + lines[i++]);
            //we add the next blank line
            file.add(index++, "");
        }

        String[] toReturn = new String[file.size()];
        for (int i = 0; i < toReturn.length; i++) {
            toReturn[i] = file.get(i);
        }
        return toReturn;
    }


    /* PRIVATE METHODS */

    /**
     * This method cleans caption.content of XML and parses line breaks.
     */
    private String[] cleanTextForSRT(Subtitle current) {
        String[] lines;
        String text = current.content;
        //add line breaks
        lines = text.split("<br />");
        //clean XML
        for (int i = 0; i < lines.length; i++) {
            //this will destroy all remaining XML tags
            lines[i] = lines[i].replaceAll("\\<.*?\\>", "");
        }
        return lines;
    }

}
