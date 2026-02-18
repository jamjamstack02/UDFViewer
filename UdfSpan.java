package com.udfviewer.app;

public class UdfSpan {
    private int startOffset;
    private int length;
    private boolean bold;
    private boolean italic;
    private boolean underline;
    private boolean space;
    private String resolvedText = "";

    public int getStartOffset() { return startOffset; }
    public void setStartOffset(int startOffset) { this.startOffset = startOffset; }

    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }

    public boolean isBold() { return bold; }
    public void setBold(boolean bold) { this.bold = bold; }

    public boolean isItalic() { return italic; }
    public void setItalic(boolean italic) { this.italic = italic; }

    public boolean isUnderline() { return underline; }
    public void setUnderline(boolean underline) { this.underline = underline; }

    public boolean isSpace() { return space; }
    public void setSpace(boolean space) { this.space = space; }

    public String getResolvedText() { return resolvedText; }
    public void setResolvedText(String resolvedText) { this.resolvedText = resolvedText; }

    public String extractText(String fullText) {
        if (fullText == null || startOffset < 0 || length <= 0) return "";
        int end = Math.min(startOffset + length, fullText.length());
        if (startOffset >= fullText.length()) return "";
        return fullText.substring(startOffset, end);
    }
}
