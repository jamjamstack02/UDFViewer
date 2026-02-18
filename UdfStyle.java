package com.udfviewer.app;

public class UdfStyle {
    private String name = "default";
    private String family = "serif";
    private float size = 12f;
    private boolean bold = false;
    private boolean italic = false;
    private int foreground = -13421773; // dark gray

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }

    public float getSize() { return size; }
    public void setSize(float size) { this.size = size; }

    public boolean isBold() { return bold; }
    public void setBold(boolean bold) { this.bold = bold; }

    public boolean isItalic() { return italic; }
    public void setItalic(boolean italic) { this.italic = italic; }

    public int getForeground() { return foreground; }
    public void setForeground(int foreground) { this.foreground = foreground; }
}
