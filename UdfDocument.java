package com.udfviewer.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UdfDocument {
    private String fullText = "";
    private List<UdfParagraph> paragraphs = new ArrayList<>();
    private Map<String, UdfStyle> styles = new HashMap<>();
    private String defaultStyleName = "hvl-default";

    public String getFullText() { return fullText; }
    public void setFullText(String fullText) { this.fullText = fullText; }

    public List<UdfParagraph> getParagraphs() { return paragraphs; }
    public void setParagraphs(List<UdfParagraph> paragraphs) { this.paragraphs = paragraphs; }

    public Map<String, UdfStyle> getStyles() { return styles; }
    public void setStyles(Map<String, UdfStyle> styles) { this.styles = styles; }

    public String getDefaultStyleName() { return defaultStyleName; }
    public void setDefaultStyleName(String defaultStyleName) { this.defaultStyleName = defaultStyleName; }

    public int getParagraphCount() { return paragraphs.size(); }

    public UdfStyle getDefaultStyle() {
        return styles.get(defaultStyleName);
    }
}
