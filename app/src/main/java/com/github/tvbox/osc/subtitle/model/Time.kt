/*
 * Class that represents the .ASS and .SSA subtitle file format
 *
 * Copyright (c) 2012 J. David Requejo
 * j[dot]david[dot]requejo[at] Gmail
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *
 * @author J. David REQUEJO
 */
package com.github.tvbox.osc.subtitle.model

/**
 * Constructor to create a time object.
 *
 * @param format supported formats: "hh:mm:ss,ms", "h:mm:ss.cs" and "h:m:s:f/fps"
 * @param value  string in the correct format
 */
class Time(format: String, value: String) {

    // in an integer we can store 24 days worth of milliseconds, no need for a long
    @JvmField
    var mseconds: Int = 0

    init {
        if (format.equals("hh:mm:ss,ms", ignoreCase = true)) {
            // this type of format:  01:02:22,501 (used in .SRT)
            val h = value.substring(0, 2).toInt()
            val m = value.substring(3, 5).toInt()
            val s = value.substring(6, 8).toInt()
            val ms = value.substring(9, 12).toInt()
            mseconds = ms + s * 1000 + m * 60000 + h * 3600000
        } else if (format.equals("h:mm:ss.cs", ignoreCase = true)) {
            // this type of format:  1:02:22.51 (used in .ASS/.SSA)
            val h = value.substring(0, 1).toInt()
            val m = value.substring(2, 4).toInt()
            val s = value.substring(5, 7).toInt()
            val cs = value.substring(8, 10).toInt()
            mseconds = cs * 10 + s * 1000 + m * 60000 + h * 3600000
        } else if (format.equals("h:m:s:f/fps", ignoreCase = true)) {
            val args = value.split("/")
            val fps = args[1].toFloat()
            val parts = args[0].split(":")
            val h = parts[0].toInt()
            val m = parts[1].toInt()
            val s = parts[2].toInt()
            val f = parts[3].toInt()
            mseconds = (f * 1000 / fps).toInt() + s * 1000 + m * 60000 + h * 3600000
        }
    }

    /**
     * Method to return a formatted value of the time stored
     *
     * @param format supported formats: "hh:mm:ss,ms", "h:mm:ss.cs" and "hhmmssff/fps"
     * @return formatted time in a string
     */
    fun getTime(format: String): String {
        // we use string builder for efficiency
        val time = StringBuilder()
        if (format.equals("hh:mm:ss,ms", ignoreCase = true)) {
            // this type of format:  01:02:22,501 (used in .SRT)
            val h = mseconds / 3600000
            val hm = h.toString()
            if (hm.length == 1) time.append('0')
            time.append(hm)
            time.append(':')
            val m = mseconds / 60000 % 60
            val mm = m.toString()
            if (mm.length == 1) time.append('0')
            time.append(mm)
            time.append(':')
            val s = mseconds / 1000 % 60
            val sm = s.toString()
            if (sm.length == 1) time.append('0')
            time.append(sm)
            time.append(',')
            val ms = mseconds % 1000
            val msm = ms.toString()
            if (msm.length == 1) time.append("00")
            else if (msm.length == 2) time.append('0')
            time.append(msm)
        } else if (format.equals("h:mm:ss.cs", ignoreCase = true)) {
            // this type of format:  1:02:22.51 (used in .ASS/.SSA)
            val h = mseconds / 3600000
            val hm = h.toString()
            if (hm.length == 1) time.append('0')
            time.append(hm)
            time.append(':')
            val m = mseconds / 60000 % 60
            val mm = m.toString()
            if (mm.length == 1) time.append('0')
            time.append(mm)
            time.append(':')
            val s = mseconds / 1000 % 60
            val sm = s.toString()
            if (sm.length == 1) time.append('0')
            time.append(sm)
            time.append('.')
            val cs = mseconds / 10 % 100
            val csm = cs.toString()
            if (csm.length == 1) time.append('0')
            time.append(csm)
        } else if (format.startsWith("hhmmssff/")) {
            // this format is used in EBU's STL
            val args = format.split("/")
            val fps = args[1].toFloat()
            // now we concatenate time
            val h = mseconds / 3600000
            val hm = h.toString()
            if (hm.length == 1) time.append('0')
            time.append(hm)
            val m = mseconds / 60000 % 60
            val mm = m.toString()
            if (mm.length == 1) time.append('0')
            time.append(mm)
            val s = mseconds / 1000 % 60
            val sm = s.toString()
            if (sm.length == 1) time.append('0')
            time.append(sm)
            val f = mseconds % 1000 * fps.toInt() / 1000
            val fm = f.toString()
            if (fm.length == 1) time.append('0')
            time.append(fm)
        } else if (format.startsWith("h:m:s:f/")) {
            // this format is used in EBU's STL
            val args = format.split("/")
            val fps = args[1].toFloat()
            // now we concatenate time
            val h = mseconds / 3600000
            time.append(h)
            time.append(':')
            val m = mseconds / 60000 % 60
            time.append(m)
            time.append(':')
            val s = mseconds / 1000 % 60
            time.append(s)
            time.append(':')
            val f = mseconds % 1000 * fps.toInt() / 1000
            time.append(f)
        } else if (format.startsWith("hh:mm:ss:ff/")) {
            // this format is used in SCC
            val args = format.split("/")
            val fps = args[1].toFloat()
            // now we concatenate time
            val h = mseconds / 3600000
            val hm = h.toString()
            if (hm.length == 1) time.append('0')
            time.append(hm)
            time.append(':')
            val m = mseconds / 60000 % 60
            val mm = m.toString()
            if (mm.length == 1) time.append('0')
            time.append(mm)
            time.append(':')
            val s = mseconds / 1000 % 60
            val sm = s.toString()
            if (sm.length == 1) time.append('0')
            time.append(sm)
            time.append(':')
            val f = mseconds % 1000 * fps.toInt() / 1000
            val fm = f.toString()
            if (fm.length == 1) time.append('0')
            time.append(fm)
        }
        return time.toString()
    }
}
