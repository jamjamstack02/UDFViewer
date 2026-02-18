package com.udfviewer.app;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * UDF belgesini A4 sayfa görünümünde render eder.
 * Zoom, metin kopyalama ve sayfa gezinmeyi destekler.
 */
public class UdfDocumentView extends ScrollView {

    private static final float BASE_TEXT_SIZE_SP = 12f;
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 3.0f;
    private static final float ZOOM_STEP = 0.25f;

    private float currentZoom = 1.0f;
    private LinearLayout pageContainer;
    private LinearLayout contentLayout;
    private UdfDocument currentDocument;
    private ScaleGestureDetector scaleGestureDetector;
    private OnZoomChangedListener onZoomChangedListener;

    public interface OnZoomChangedListener {
        void onZoomChanged(float zoom);
    }

    public void setOnZoomChangedListener(OnZoomChangedListener l) {
        this.onZoomChangedListener = l;
    }

    public UdfDocumentView(Context context) {
        super(context);
        init();
    }

    public UdfDocumentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public UdfDocumentView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setBackgroundColor(0xFFE0E0E0);
        setPadding(dp(16), dp(24), dp(16), dp(24));

        pageContainer = new LinearLayout(getContext());
        pageContainer.setOrientation(LinearLayout.VERTICAL);
        pageContainer.setBackgroundColor(0xFFFFFFFF);
        pageContainer.setElevation(dp(4));

        int pagePaddingPx = dp(56);
        pageContainer.setPadding(pagePaddingPx, pagePaddingPx, pagePaddingPx, pagePaddingPx);

        contentLayout = new LinearLayout(getContext());
        contentLayout.setOrientation(LinearLayout.VERTICAL);

        pageContainer.addView(contentLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        addView(pageContainer, pageParams);

        // Pinch-to-zoom
        scaleGestureDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float factor = detector.getScaleFactor();
                        float newZoom = currentZoom * factor;
                        newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
                        if (Math.abs(newZoom - currentZoom) > 0.01f) {
                            currentZoom = newZoom;
                            applyZoom();
                            if (onZoomChangedListener != null) {
                                onZoomChangedListener.onZoomChanged(currentZoom);
                            }
                        }
                        return true;
                    }
                });
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleGestureDetector.onTouchEvent(ev);
        return super.onTouchEvent(ev);
    }

    public void setDocument(UdfDocument document) {
        this.currentDocument = document;
        renderDocument();
    }

    private void renderDocument() {
        contentLayout.removeAllViews();
        if (currentDocument == null) return;

        UdfStyle defaultStyle = currentDocument.getDefaultStyle();
        List<UdfParagraph> paragraphs = currentDocument.getParagraphs();

        for (UdfParagraph paragraph : paragraphs) {
            TextView tv = createParagraphView(paragraph, defaultStyle);
            contentLayout.addView(tv);
        }
    }

    private TextView createParagraphView(UdfParagraph paragraph, UdfStyle defaultStyle) {
        TextView tv = new TextView(getContext());

        // Hizalama
        switch (paragraph.getAlignment()) {
            case 1: tv.setGravity(Gravity.CENTER_HORIZONTAL); break;
            case 2: tv.setGravity(Gravity.END); break;
            case 3: tv.setGravity(Gravity.START); break; // justify Android'de tam destek yok
            default: tv.setGravity(Gravity.START); break;
        }

        // Yazı tipi ve boyut
        float baseSize = defaultStyle != null ? defaultStyle.getSize() : BASE_TEXT_SIZE_SP;
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSize * currentZoom);

        // Metin kopyalanabilir olsun
        tv.setTextIsSelectable(true);

        // Boş paragraf ise sadece boşluk ekle
        if (paragraph.isEmpty() || paragraph.getSpans().isEmpty()) {
            tv.setText(" ");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = dp(4);
            tv.setLayoutParams(params);
            return tv;
        }

        // SpannableString ile biçimlendirme uygula
        SpannableStringBuilder ssb = new SpannableStringBuilder();

        List<UdfSpan> spans = paragraph.getSpans();
        for (UdfSpan span : spans) {
            String text = span.getResolvedText();
            if (text == null || text.isEmpty()) continue;

            int start = ssb.length();
            ssb.append(text);
            int end = ssb.length();

            if (span.isBold() && span.isItalic()) {
                ssb.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (span.isBold()) {
                ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (span.isItalic()) {
                ssb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            if (span.isUnderline()) {
                ssb.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        tv.setText(ssb);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(6);
        tv.setLayoutParams(params);

        return tv;
    }

    public void zoomIn() {
        if (currentZoom < MAX_ZOOM) {
            currentZoom = Math.min(currentZoom + ZOOM_STEP, MAX_ZOOM);
            applyZoom();
        }
    }

    public void zoomOut() {
        if (currentZoom > MIN_ZOOM) {
            currentZoom = Math.max(currentZoom - ZOOM_STEP, MIN_ZOOM);
            applyZoom();
        }
    }

    public void resetZoom() {
        currentZoom = 1.0f;
        applyZoom();
    }

    private void applyZoom() {
        if (currentDocument == null) return;
        // Tüm TextView'lerin boyutunu güncelle
        updateTextSizes(contentLayout);
    }

    private void updateTextSizes(LinearLayout layout) {
        UdfStyle defaultStyle = currentDocument.getDefaultStyle();
        float baseSize = defaultStyle != null ? defaultStyle.getSize() : BASE_TEXT_SIZE_SP;

        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSize * currentZoom);
            }
        }
    }

    public String getAllText() {
        if (currentDocument == null) return "";
        return currentDocument.getFullText();
    }

    public float getCurrentZoom() { return currentZoom; }

    private int dp(int dp) {
        return (int) (dp * getContext().getResources().getDisplayMetrics().density);
    }
}
