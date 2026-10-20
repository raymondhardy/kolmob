package com.kolmob.awt;

import java.io.Serializable;

/** Android shim for the parts of java.awt.Color (remapped from java/awt/ at build time, since apps cannot define java.* classes) that KoLmafia's core uses (plain RGBA value). */
public class Color implements Serializable {
  public static final Color white = new Color(255, 255, 255);
  public static final Color WHITE = white;
  public static final Color lightGray = new Color(192, 192, 192);
  public static final Color LIGHT_GRAY = lightGray;
  public static final Color gray = new Color(128, 128, 128);
  public static final Color GRAY = gray;
  public static final Color darkGray = new Color(64, 64, 64);
  public static final Color DARK_GRAY = darkGray;
  public static final Color black = new Color(0, 0, 0);
  public static final Color BLACK = black;
  public static final Color red = new Color(255, 0, 0);
  public static final Color RED = red;
  public static final Color pink = new Color(255, 175, 175);
  public static final Color PINK = pink;
  public static final Color orange = new Color(255, 200, 0);
  public static final Color ORANGE = orange;
  public static final Color yellow = new Color(255, 255, 0);
  public static final Color YELLOW = yellow;
  public static final Color green = new Color(0, 255, 0);
  public static final Color GREEN = green;
  public static final Color magenta = new Color(255, 0, 255);
  public static final Color MAGENTA = magenta;
  public static final Color cyan = new Color(0, 255, 255);
  public static final Color CYAN = cyan;
  public static final Color blue = new Color(0, 0, 255);
  public static final Color BLUE = blue;

  private final int value;

  public Color(int r, int g, int b) {
    this(r, g, b, 255);
  }

  public Color(int r, int g, int b, int a) {
    value = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
  }

  public Color(int rgb) {
    value = 0xff000000 | rgb;
  }

  public int getRed() {
    return (value >> 16) & 0xFF;
  }

  public int getGreen() {
    return (value >> 8) & 0xFF;
  }

  public int getBlue() {
    return value & 0xFF;
  }

  public int getAlpha() {
    return (value >> 24) & 0xff;
  }

  public int getRGB() {
    return value;
  }

  public static Color decode(String nm) throws NumberFormatException {
    int i = Integer.decode(nm);
    return new Color((i >> 16) & 0xFF, (i >> 8) & 0xFF, i & 0xFF);
  }

  @Override
  public int hashCode() {
    return value;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof Color && ((Color) obj).value == this.value;
  }

  @Override
  public String toString() {
    return getClass().getName() + "[r=" + getRed() + ",g=" + getGreen() + ",b=" + getBlue() + "]";
  }
}
