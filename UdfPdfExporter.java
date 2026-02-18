package com.udfviewer.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.print.PrintAttributes;
import android.print.pdf.PrintedPdfDocument;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * UdfDocument'i standart Android PdfDocument API'siyle PDF'e dönüştürür.
 * A4 sayfa boyutu, Times New Roman, bold/italic/underline, hizalama desteği.
 */
public class UdfPdfExporter {

    // A4 @ 72dpi (Android PDF birim = 1/72 inç)
    private static final int PAGE_WIDTH  = 595;  // 8.27 inç
    private static final int PAGE_HEIGHT = 842;  // 11.69 inç
    private static final int MARGIN      = 72;   // ~2.54 cm
    private static final float LINE_HEIGHT_FACTOR = 1.6f;

    public interface ExportCallback {
        void onSuccess(File pdfFile);
        void onError(String message);
    }

    private final Context context;

    public UdfPdfExporter(Context context) {
        this.context = context.getApplicationContext();
    }

    public void export(UdfDocument document, String baseFileName, ExportCallback callback) {
        new Thread(() -> {
            try {
                File pdfFile = doExport(document, baseFileName);
                callback.onSuccess(pdfFile);
            } catch (Exception e) {
                callback.onError("PDF oluşturulamadı: " + e.getMessage());
            }
        }).start();
    }

    private File doExport(UdfDocument document, String baseFileName) throws Exception {
        PdfDocument pdf = new PdfDocument();

        float textSize = 12f;
        UdfStyle defaultStyle = document.getDefaultStyle();
        if (defaultStyle != null) textSize = defaultStyle.getSize();

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);

        float lineHeight = textSize * LINE_HEIGHT_FACTOR;
        float contentWidth = PAGE_WIDTH - 2f * MARGIN;
        float x = MARGIN;
        float y = MARGIN + textSize;

        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create();
        PdfDocument.Page page = pdf.startPage(pageInfo);
        Canvas canvas = page.getCanvas();
        int pageNumber = 1;

        List<UdfParagraph> paragraphs = document.getParagraphs();

        for (UdfParagraph paragraph : paragraphs) {
            // Yeni sayfaya geç
            if (y > PAGE_HEIGHT - MARGIN) {
                pdf.finishPage(page);
                pageNumber++;
                PdfDocument.PageInfo nextPageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
                page = pdf.startPage(nextPageInfo);
                canvas = page.getCanvas();
                y = MARGIN + textSize;
            }

            if (paragraph.isEmpty()) {
                y += lineHeight * 0.5f;
                continue;
            }

            List<UdfSpan> spans = paragraph.getSpans();
            if (spans.isEmpty()) {
                y += lineHeight * 0.5f;
                continue;
            }

            // Tüm paragraf metnini ölç ve kelime sar
            String paraText = paragraph.getResolvedText();
            if (paraText == null || paraText.isEmpty()) {
                y += lineHeight * 0.5f;
                continue;
            }

            // İlk span'ın stilini al (paragraf bazlı basit yaklaşım)
            UdfSpan firstSpan = spans.get(0);
            applyStyle(paint, firstSpan, textSize);

            // Hizalama
            Paint.Align align = Paint.Align.LEFT;
            switch (paragraph.getAlignment()) {
                case 1: align = Paint.Align.CENTER; break;
                case 2: align = Paint.Align.RIGHT;  break;
            }
            paint.setTextAlign(align);

            // Kelime sarma
            String[] words = paraText.split(" ");
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                String test = line.length() == 0 ? words[i] : line + " " + words[i];
                if (paint.measureText(test) > contentWidth && line.length() > 0) {
                    drawLine(canvas, paint, line.toString(), x, y, align, contentWidth);
                    y += lineHeight;
                    line = new StringBuilder(words[i]);

                    if (y > PAGE_HEIGHT - MARGIN) {
                        pdf.finishPage(page);
                        pageNumber++;
                        PdfDocument.PageInfo npi = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
                        page = pdf.startPage(npi);
                        canvas = page.getCanvas();
                        y = MARGIN + textSize;
                    }
                } else {
                    line = new StringBuilder(test);
                }
            }
            if (line.length() > 0) {
                drawLine(canvas, paint, line.toString(), x, y, align, contentWidth);
                y += lineHeight;
            }

            // Paragraf sonu boşluk
            y += lineHeight * 0.2f;

            // Stili sıfırla
            paint.setTextAlign(Paint.Align.LEFT);
        }

        pdf.finishPage(page);

        // Dosyayı kaydet
        File outputDir = new File(context.getCacheDir(), "pdf_exports");
        outputDir.mkdirs();
        String safeFileName = baseFileName.replace(".udf", "").replaceAll("[^a-zA-Z0-9._-]", "_");
        File pdfFile = new File(outputDir, safeFileName + ".pdf");

        FileOutputStream fos = new FileOutputStream(pdfFile);
        pdf.writeTo(fos);
        fos.close();
        pdf.close();

        return pdfFile;
    }

    private void drawLine(Canvas canvas, Paint paint, String text,
                          float x, float y, Paint.Align align, float contentWidth) {
        float drawX;
        switch (align) {
            case CENTER: drawX = x + contentWidth / 2f; break;
            case RIGHT:  drawX = x + contentWidth; break;
            default:     drawX = x; break;
        }
        canvas.drawText(text, drawX, y, paint);

        // Underline manuel (PDF API'sinde underline yok)
        if (paint.isUnderlineText()) {
            float textWidth = paint.measureText(text);
            float underlineY = y + paint.descent();
            float startX = align == Paint.Align.CENTER ? drawX - textWidth / 2f :
                           align == Paint.Align.RIGHT  ? drawX - textWidth : drawX;
            Paint linePaint = new Paint(paint);
            linePaint.setStrokeWidth(0.8f);
            canvas.drawLine(startX, underlineY, startX + textWidth, underlineY, linePaint);
        }
    }

    private void applyStyle(Paint paint, UdfSpan span, float baseSize) {
        paint.setTextSize(baseSize);
        paint.setUnderlineText(span.isUnderline());

        int style;
        if (span.isBold() && span.isItalic()) style = Typeface.BOLD_ITALIC;
        else if (span.isBold())               style = Typeface.BOLD;
        else if (span.isItalic())             style = Typeface.ITALIC;
        else                                  style = Typeface.NORMAL;

        Typeface tf = Typeface.create("serif", style);
        paint.setTypeface(tf);
    }

    /** PDF'i FileProvider üzerinden paylaşma Intent'i oluşturur */
    public static Intent createShareIntent(Context context, File pdfFile) {
        Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".provider", pdfFile);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, pdfFile.getName());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }
}
