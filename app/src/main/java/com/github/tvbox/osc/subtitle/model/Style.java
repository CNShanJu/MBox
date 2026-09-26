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

package com.github.tvbox.osc.subtitle.model;

public class Style {

	private static int styleCounter;

	/**
	 * Constructor that receives a String to use a its identifier
	 * 
	 * @param styleName
	 *            = identifier of this style
	 */
	public Style(String styleName) {
		this.iD = styleName;
	}

	/**
	 * Constructor that receives a String with the new styleName and a style to
	 * copy
	 * 
	 * @param styleName
	 * @param style
	 */
	public Style(String styleName, Style style) {
		this.iD = styleName;
		this.font = style.font;
		this.fontSize = style.fontSize;
		this.color = style.color;
		this.backgroundColor = style.backgroundColor;
		this.textAlign = style.textAlign;
		this.italic = style.italic;
		this.underline = style.underline;
		this.bold = style.bold;

	}

	/* ATTRIBUTES */
	public String iD;
	public String font;
	public String fontSize;
	/** colors are stored as 8 chars long RGBA */
	public String color;
	public String backgroundColor;
	public String textAlign = "";

	public boolean italic;
	public boolean bold;
	public boolean underline;

	/* METHODS */

	/**
	 * To get the string containing the hex value to put into color or
	 * background color
	 * 
	 * @param format
	 *            supported: "name", "&HBBGGRR", "&HAABBGGRR",
	 *            "decimalCodedBBGGRR", "decimalCodedAABBGGRR"
	 * @param value
	 *            RRGGBBAA string
	 * @return
	 */
	public static String getRGBValue(String format, String value) {
		String color = null;
		if (format.equalsIgnoreCase("name")) {
			// standard color format from W3C
			if (value.equals("transparent"))
				color = "00000000";
			else if (value.equals("black"))
				color = "000000ff";
			else if (value.equals("silver"))
				color = "c0c0c0ff";
			else if (value.equals("gray"))
				color = "808080ff";
			else if (value.equals("white"))
				color = "ffffffff";
			else if (value.equals("maroon"))
				color = "800000ff";
			else if (value.equals("red"))
				color = "ff0000ff";
			else if (value.equals("purple"))
				color = "800080ff";
			else if (value.equals("fuchsia"))
				color = "ff00ffff";
			else if (value.equals("magenta"))
				color = "ff00ffff ";
			else if (value.equals("green"))
				color = "008000ff";
			else if (value.equals("lime"))
				color = "00ff00ff";
			else if (value.equals("olive"))
				color = "808000ff";
			else if (value.equals("yellow"))
				color = "ffff00ff";
			else if (value.equals("navy"))
				color = "000080ff";
			else if (value.equals("blue"))
				color = "0000ffff";
			else if (value.equals("teal"))
				color = "008080ff";
			else if (value.equals("aqua"))
				color = "00ffffff";
			else if (value.equals("cyan"))
				color = "00ffffff ";
		} else if (format.equalsIgnoreCase("&HBBGGRR")) {
			// hex format from SSA: &HBBGGRR[&] → RRGGBBAA(alpha=ff)
			color = hexToRGBA(value, 6);
		} else if (format.equalsIgnoreCase("&HAABBGGRR")) {
			// hex format from ASS: &HAABBGGRR[&] → RRGGBBAA
			color = hexToRGBA(value, 8);
		} else if (format.equalsIgnoreCase("decimalCodedBBGGRR")) {
			// normal format from SSA
			color = Integer.toHexString(Integer.parseInt(value));
			// any missing 0s are filled in
			while (color.length() < 6)
				color = "0" + color;
			// order is reversed
			color = color.substring(4) + color.substring(2, 4)
					+ color.substring(0, 2) + "ff";
		} else if (format.equalsIgnoreCase("decimalCodedAABBGGRR")) {
			// normal format from ASS
			color = Long.toHexString(Long.parseLong(value));
			// any missing 0s are filled in
			while (color.length() < 8)
				color = "0" + color;
			// order is reversed
			color = color.substring(6) + color.substring(4, 6)
					+ color.substring(2, 4) + color.substring(0, 2);
		}
		return color;
	}

	/**
	 * ASS/SSA 的 {@code &HBBGGRR}/{@code &HAABBGGRR} 色值 → 统一的 8 字符 RGBA(RRGGBBAA,Android 语义)。
	 * <p>
	 * 修正点(原实现每个通道只截 1 个字符,任意颜色都会被拼错):
	 * <ul>
	 *   <li>按通道成对截取:ASS 的 {@code AABBGGRR} 取 {@code (0,2)/(2,4)/(4,6)/(6,8)},
	 *       SSA 的 {@code BBGGRR} 取 {@code (0,2)/(2,4)/(4,6)} 并补不透明;</li>
	 *   <li>调用方传进来的是带前缀的原始写法({@code &H00FFFFFF},行尾还可能带 {@code &}),
	 *       这里先剥掉 {@code &H}/{@code &} 再按位数对齐(不足左侧补 0、超出取低位);</li>
	 *   <li>ASS 的 alpha 与 Android 相反({@code 00}=不透明、{@code FF}=全透明),这里翻转,
	 *       与 SSA 分支补的 {@code ff}(不透明)保持同一语义。</li>
	 * </ul>
	 *
	 * @param value 原始色值(如 {@code &H00FFFFFF} / {@code &HFFFFFF&} / {@code 00FFFFFF})
	 * @param digits 期望的十六进制位数:6=BBGGRR(SSA)、8=AABBGGRR(ASS)
	 * @return RRGGBBAA;入参非法返回 null
	 */
	private static String hexToRGBA(String value, int digits) {
		if (value == null) return null;
		String hex = value.trim();
		if (hex.regionMatches(true, 0, "&H", 0, 2)) hex = hex.substring(2);
		hex = hex.replace("&", "").trim();
		if (hex.isEmpty() || !hex.matches("(?i)[0-9a-f]+")) return null;
		if (hex.length() > digits) hex = hex.substring(hex.length() - digits);
		while (hex.length() < digits) hex = "0" + hex;
		String rr;
		String gg;
		String bb;
		String aa;
		if (digits >= 8) {
			// AABBGGRR
			aa = invertByte(hex.substring(0, 2));
			bb = hex.substring(2, 4);
			gg = hex.substring(4, 6);
			rr = hex.substring(6, 8);
		} else {
			// BBGGRR(无 alpha → 不透明)
			bb = hex.substring(0, 2);
			gg = hex.substring(2, 4);
			rr = hex.substring(4, 6);
			aa = "ff";
		}
		return (rr + gg + bb + aa).toLowerCase(java.util.Locale.ROOT);
	}

	/** ASS/SSA 的 alpha 与 Android 相反(00=不透明),按字节翻转 */
	private static String invertByte(String hexByte) {
		int v = Integer.parseInt(hexByte, 16);
		String s = Integer.toHexString(255 - v);
		return s.length() < 2 ? "0" + s : s;
	}

	public static String defaultID() {
		return "default" + styleCounter++;
	}

}
