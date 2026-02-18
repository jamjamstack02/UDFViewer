package com.udfviewer.app;

import java.util.ArrayList;
import java.util.List;

public class UdfParagraph {
    // Alignment: 0=sol, 1=orta, 2=sağ, 3=justify
    private int alignment = 0;
    private List<UdfSpan> spans = new ArrayList<>();
    private String resolvedText = "";

    public int getAlignment() { return alignment; }
    public void setAlignment(int alignment) { this.alignment = alignment; }

    public List<UdfSpan> getSpans() { return spans; }
    public void addSpan(UdfSpan span) { spans.add(span); }

    public String getResolvedText() { return resolvedText; }

    public void resolveText(String fullText) {
        StringBuilder sb = new StringBuilder();
        for (UdfSpan span : spans) {
            String spanText = span.extractText(fullText);
            span.setResolvedText(spanText);
            sb.append(spanText);
        }
        resolvedText = sb.toString();
    }

    public boolean isEmpty() {
        return resolvedText.trim().isEmpty();
    }
}
