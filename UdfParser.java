package com.udfviewer.app;

import android.content.Context;
import android.net.Uri;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * UDF (Ulusal Doküman Formatı) dosyasını parse eder.
 * UDF = ZIP arşivi içinde content.xml barındırır.
 * content.xml şeması:
 *   <template>
 *     <content><![CDATA[düz metin]]></content>
 *     <properties> sayfa ayarları </properties>
 *     <elements resolver="...">
 *       <paragraph Alignment="...">
 *         <content bold="true" startOffset="N" length="M" />
 *         ...
 *       </paragraph>
 *     </elements>
 *     <styles>
 *       <style name="..." family="..." size="..." bold="..." italic="..." />
 *     </styles>
 *   </template>
 */
public class UdfParser {

    private final Context context;

    public UdfParser(Context context) {
        this.context = context;
    }

    public UdfDocument parse(Uri uri) throws Exception {
        InputStream inputStream = context.getContentResolver().openInputStream(uri);
        if (inputStream == null) throw new Exception("Dosya açılamadı");

        String contentXml = null;

        // ZIP içinden content.xml'i oku
        ZipInputStream zis = new ZipInputStream(inputStream);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if ("content.xml".equals(entry.getName())) {
                byte[] buffer = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    sb.append(new String(buffer, 0, len, "UTF-8"));
                }
                contentXml = sb.toString();
                break;
            }
        }
        zis.close();

        if (contentXml == null) throw new Exception("Geçersiz UDF dosyası: content.xml bulunamadı");

        return parseContentXml(contentXml);
    }

    private UdfDocument parseContentXml(String xml) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new StringReader(xml));

        UdfDocument document = new UdfDocument();
        String fullText = null;
        Map<String, UdfStyle> styles = new HashMap<>();
        List<UdfParagraph> paragraphs = new ArrayList<>();
        String defaultStyleResolver = "hvl-default";

        String currentTag = null;
        UdfParagraph currentParagraph = null;
        boolean inElements = false;
        boolean inStyles = false;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    currentTag = parser.getName();

                    if ("content".equals(currentTag) && !inElements) {
                        // Ana metin içeriği - bir sonraki TEXT event'te CDATA gelecek
                    } else if ("elements".equals(currentTag)) {
                        inElements = true;
                        defaultStyleResolver = getAttr(parser, "resolver", "hvl-default");
                    } else if ("styles".equals(currentTag)) {
                        inStyles = true;
                    } else if (inElements && "paragraph".equals(currentTag)) {
                        currentParagraph = new UdfParagraph();
                        String align = getAttr(parser, "Alignment", "0");
                        currentParagraph.setAlignment(Integer.parseInt(align));
                    } else if (inElements && currentParagraph != null && "content".equals(currentTag)) {
                        UdfSpan span = new UdfSpan();
                        span.setStartOffset(parseInt(getAttr(parser, "startOffset", "0")));
                        span.setLength(parseInt(getAttr(parser, "length", "0")));
                        span.setBold("true".equals(getAttr(parser, "bold", "false")));
                        span.setItalic("true".equals(getAttr(parser, "italic", "false")));
                        span.setUnderline("true".equals(getAttr(parser, "underline", "false")));
                        currentParagraph.addSpan(span);
                    } else if (inElements && currentParagraph != null && "space".equals(currentTag)) {
                        UdfSpan span = new UdfSpan();
                        span.setStartOffset(parseInt(getAttr(parser, "startOffset", "0")));
                        span.setLength(parseInt(getAttr(parser, "length", "0")));
                        span.setBold("true".equals(getAttr(parser, "bold", "false")));
                        span.setSpace(true);
                        currentParagraph.addSpan(span);
                    } else if (inStyles && "style".equals(currentTag)) {
                        UdfStyle style = new UdfStyle();
                        style.setName(getAttr(parser, "name", "default"));
                        style.setFamily(getAttr(parser, "family", "serif"));
                        style.setSize(parseFloat(getAttr(parser, "size", "12")));
                        style.setBold("true".equals(getAttr(parser, "bold", "false")));
                        style.setItalic("true".equals(getAttr(parser, "italic", "false")));
                        styles.put(style.getName(), style);
                    }
                    break;

                case XmlPullParser.TEXT:
                case XmlPullParser.CDSECT:
                    if (!inElements && !inStyles && "content".equals(currentTag)) {
                        fullText = parser.getText();
                    }
                    break;

                case XmlPullParser.END_TAG:
                    String endTag = parser.getName();
                    if ("elements".equals(endTag)) {
                        inElements = false;
                    } else if ("styles".equals(endTag)) {
                        inStyles = false;
                    } else if (inElements && "paragraph".equals(endTag) && currentParagraph != null) {
                        paragraphs.add(currentParagraph);
                        currentParagraph = null;
                    }
                    currentTag = null;
                    break;
            }
            eventType = parser.next();
        }

        // Paragrafları metinden çıkar
        if (fullText != null) {
            document.setFullText(fullText);
            for (UdfParagraph para : paragraphs) {
                para.resolveText(fullText);
            }
        }

        document.setParagraphs(paragraphs);
        document.setStyles(styles);
        document.setDefaultStyleName(defaultStyleResolver);

        return document;
    }

    private String getAttr(XmlPullParser parser, String name, String defaultVal) {
        String val = parser.getAttributeValue(null, name);
        return val != null ? val : defaultVal;
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private float parseFloat(String s) {
        try { return Float.parseFloat(s); } catch (Exception e) { return 12f; }
    }
}
