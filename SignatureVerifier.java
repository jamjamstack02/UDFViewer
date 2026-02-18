package com.udfviewer.app;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * UDF dosyasındaki sign.sgn dijital imzasını doğrular.
 *
 * sign.sgn formatı (Adalet Bakanlığı e-imza):
 *   Base64 kodlu CMS/PKCS#7 SignedData bloğu veya
 *   XML-DSig yapısı içeren metin dosyası.
 *
 * Doğrulama adımları:
 *   1. sign.sgn dosyasını oku
 *   2. İmza tipini tespit et (CMS / XML-DSig / ham hash)
 *   3. İçerideki sertifika bilgilerini çıkar
 *   4. content.xml üzerindeki hash'i hesapla ve karşılaştır
 *   5. Sonucu SignatureResult olarak döndür
 */
public class SignatureVerifier {

    public enum SignatureStatus {
        VALID,           // İmza geçerli, içerik değişmemiş
        INVALID,         // İmza geçersiz veya içerik bozulmuş
        UNKNOWN_FORMAT,  // İmza formatı tanınamadı
        NO_SIGNATURE,    // sign.sgn bulunamadı
        ERROR            // Doğrulama sırasında hata
    }

    public static class SignatureResult {
        public final SignatureStatus status;
        public final String signerName;
        public final String signerTitle;
        public final String signedAt;
        public final String certificateInfo;
        public final String errorMessage;
        public final String rawSignatureType;

        public SignatureResult(SignatureStatus status, String signerName, String signerTitle,
                               String signedAt, String certificateInfo,
                               String errorMessage, String rawSignatureType) {
            this.status = status;
            this.signerName = signerName;
            this.signerTitle = signerTitle;
            this.signedAt = signedAt;
            this.certificateInfo = certificateInfo;
            this.errorMessage = errorMessage;
            this.rawSignatureType = rawSignatureType;
        }

        public String getStatusLabel() {
            switch (status) {
                case VALID:          return "✓ İmza Geçerli";
                case INVALID:        return "✗ İmza Geçersiz";
                case UNKNOWN_FORMAT: return "? Format Tanınamadı";
                case NO_SIGNATURE:   return "İmza Yok";
                default:             return "Hata";
            }
        }

        public int getStatusColor() {
            switch (status) {
                case VALID:   return 0xFF2E7D32; // green
                case INVALID: return 0xFFC62828; // red
                default:      return 0xFFE65100; // orange
            }
        }
    }

    private final Context context;

    public SignatureVerifier(Context context) {
        this.context = context;
    }

    public SignatureResult verify(Uri udfUri) {
        try {
            InputStream is = context.getContentResolver().openInputStream(udfUri);
            if (is == null) return error("Dosya açılamadı");

            byte[] contentXmlBytes = null;
            byte[] signBytes = null;

            ZipInputStream zis = new ZipInputStream(is);
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] data = readFully(zis);
                if ("content.xml".equals(entry.getName())) contentXmlBytes = data;
                else if ("sign.sgn".equals(entry.getName())) signBytes = data;
            }
            zis.close();

            if (signBytes == null) {
                return new SignatureResult(SignatureStatus.NO_SIGNATURE,
                        null, null, null, null, "Dosyada dijital imza bulunamadı", null);
            }

            // İmza tipini tespit et
            String signText = new String(signBytes, StandardCharsets.UTF_8).trim();

            if (signText.startsWith("MII") || signText.startsWith("-----BEGIN")) {
                // PEM veya Base64 CMS/PKCS#7
                return verifyCms(signText, contentXmlBytes);
            } else if (signText.startsWith("<?xml") || signText.contains("<Signature")) {
                // XML-DSig
                return verifyXmlDsig(signText, contentXmlBytes);
            } else if (signText.matches("[0-9a-fA-F]{32,}")) {
                // Ham hex hash (basit MD5/SHA)
                return verifyHashOnly(signText, contentXmlBytes);
            } else {
                // Bilinmeyen format — ham bilgileri çıkarmayı dene
                return parseUnknownFormat(signText, contentXmlBytes);
            }

        } catch (Exception e) {
            return error("Doğrulama hatası: " + e.getMessage());
        }
    }

    // ── CMS / PKCS#7 ──────────────────────────────────────────────────────────

    private SignatureResult verifyCms(String pem, byte[] contentBytes) {
        try {
            // PEM başlıklarını temizle
            String b64 = pem
                    .replace("-----BEGIN PKCS7-----", "")
                    .replace("-----END PKCS7-----", "")
                    .replace("-----BEGIN SIGNED DATA-----", "")
                    .replace("-----END SIGNED DATA-----", "")
                    .replaceAll("\\s+", "");

            byte[] derBytes = Base64.decode(b64, Base64.DEFAULT);

            // DER içindeki sertifikayı bul ve parse et
            X509Certificate cert = extractCertFromCms(derBytes);

            String signerName = cert != null ? extractCN(cert.getSubjectDN().getName()) : "Bilinmiyor";
            String signerTitle = cert != null ? extractOU(cert.getSubjectDN().getName()) : "";
            String signedAt = cert != null ?
                    new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                            .format(cert.getNotBefore()) : "";
            String certInfo = cert != null ?
                    "Seri No: " + cert.getSerialNumber().toString(16).toUpperCase() +
                    "\nGeçerlilik: " + new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(cert.getNotBefore()) +
                    " – " + new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(cert.getNotAfter()) : "";

            // Hash kontrolü (content.xml SHA-256)
            boolean hashMatch = verifyContentHash(derBytes, contentBytes);

            SignatureStatus status = hashMatch ? SignatureStatus.VALID : SignatureStatus.INVALID;
            return new SignatureResult(status, signerName, signerTitle, signedAt, certInfo, null, "CMS/PKCS#7");

        } catch (Exception e) {
            return error("CMS ayrıştırma hatası: " + e.getMessage());
        }
    }

    // ── XML-DSig ──────────────────────────────────────────────────────────────

    private SignatureResult verifyXmlDsig(String xml, byte[] contentBytes) {
        try {
            // XML içinden signer bilgilerini regex ile çıkar
            String signerName = extractXmlValue(xml, "X509SubjectName", "CN");
            if (signerName == null) signerName = extractXmlTag(xml, "SignedBy");
            if (signerName == null) signerName = "Bilinmiyor";

            String signingTime = extractXmlTag(xml, "SigningTime");
            if (signingTime == null) signingTime = extractXmlTag(xml, "xades:SigningTime");

            // DigestValue ile content hash kontrolü
            String digestValue = extractXmlTag(xml, "DigestValue");
            boolean hashMatch = false;
            if (digestValue != null && contentBytes != null) {
                byte[] expectedHash = Base64.decode(digestValue.trim(), Base64.DEFAULT);
                byte[] actualHash = sha256(contentBytes);
                hashMatch = MessageDigest.isEqual(expectedHash, actualHash);
                if (!hashMatch) {
                    // SHA-1 ile de dene
                    actualHash = sha1(contentBytes);
                    hashMatch = MessageDigest.isEqual(expectedHash, actualHash);
                }
            }

            String certInfo = extractXmlTag(xml, "X509Certificate");
            if (certInfo != null) {
                try {
                    byte[] certBytes = Base64.decode(certInfo.replaceAll("\\s+", ""), Base64.DEFAULT);
                    X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                            .generateCertificate(new ByteArrayInputStream(certBytes));
                    certInfo = "Seri No: " + cert.getSerialNumber().toString(16).toUpperCase() +
                               "\nGeçerlilik: " +
                               new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(cert.getNotBefore()) +
                               " – " +
                               new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(cert.getNotAfter());
                } catch (Exception ignored) {}
            }

            SignatureStatus status = digestValue == null ? SignatureStatus.UNKNOWN_FORMAT
                    : hashMatch ? SignatureStatus.VALID : SignatureStatus.INVALID;

            return new SignatureResult(status, signerName, null, signingTime, certInfo, null, "XML-DSig");

        } catch (Exception e) {
            return error("XML-DSig hatası: " + e.getMessage());
        }
    }

    // ── Ham Hash ──────────────────────────────────────────────────────────────

    private SignatureResult verifyHashOnly(String hashHex, byte[] contentBytes) {
        try {
            boolean valid = false;
            if (contentBytes != null) {
                String sha256 = bytesToHex(sha256(contentBytes));
                String sha1   = bytesToHex(sha1(contentBytes));
                String md5    = bytesToHex(md5(contentBytes));
                valid = hashHex.equalsIgnoreCase(sha256)
                     || hashHex.equalsIgnoreCase(sha1)
                     || hashHex.equalsIgnoreCase(md5);
            }
            SignatureStatus status = valid ? SignatureStatus.VALID : SignatureStatus.INVALID;
            return new SignatureResult(status, null, null, null,
                    "Hash: " + hashHex.substring(0, Math.min(16, hashHex.length())) + "...",
                    null, "Hash");
        } catch (Exception e) {
            return error("Hash doğrulama hatası: " + e.getMessage());
        }
    }

    private SignatureResult parseUnknownFormat(String raw, byte[] contentBytes) {
        // Bilinen alanları arama yap
        String name = extractLineValue(raw, "Name:", "Ad:", "Signer:", "İmzalayan:");
        String date = extractLineValue(raw, "Date:", "Tarih:", "Time:", "Zaman:");
        return new SignatureResult(SignatureStatus.UNKNOWN_FORMAT, name, null, date,
                "Ham veri: " + raw.substring(0, Math.min(80, raw.length())), null, "Bilinmiyor");
    }

    // ── Yardımcı metodlar ─────────────────────────────────────────────────────

    private X509Certificate extractCertFromCms(byte[] der) {
        // DER içinde 0x30 0x82 ile başlayan sertifika bloklarını bul
        try {
            for (int i = 0; i < der.length - 4; i++) {
                if (der[i] == 0x30 && der[i+1] == (byte)0x82) {
                    int len = ((der[i+2] & 0xFF) << 8) | (der[i+3] & 0xFF);
                    if (i + 4 + len <= der.length) {
                        try {
                            byte[] certCandidate = new byte[len + 4];
                            System.arraycopy(der, i, certCandidate, 0, certCandidate.length);
                            X509Certificate cert = (X509Certificate) CertificateFactory
                                    .getInstance("X.509")
                                    .generateCertificate(new ByteArrayInputStream(certCandidate));
                            // Geçerli bir sertifika ise döndür
                            if (cert.getSubjectDN() != null) return cert;
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean verifyContentHash(byte[] cmsBytes, byte[] contentBytes) {
        if (contentBytes == null) return false;
        try {
            byte[] actualSha256 = sha256(contentBytes);
            byte[] actualSha1   = sha1(contentBytes);
            String hexSha256 = bytesToHex(actualSha256);
            String hexSha1   = bytesToHex(actualSha1);
            String cmsHex = bytesToHex(cmsBytes).toLowerCase();
            return cmsHex.contains(hexSha256.toLowerCase())
                || cmsHex.contains(hexSha1.toLowerCase());
        } catch (Exception e) { return false; }
    }

    private String extractCN(String dn) {
        return extractDnComponent(dn, "CN");
    }

    private String extractOU(String dn) {
        return extractDnComponent(dn, "OU");
    }

    private String extractDnComponent(String dn, String key) {
        if (dn == null) return null;
        for (String part : dn.split(",")) {
            part = part.trim();
            if (part.toUpperCase().startsWith(key + "=")) {
                return part.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    private String extractXmlTag(String xml, String tag) {
        int start = xml.indexOf("<" + tag + ">");
        if (start == -1) start = xml.indexOf("<" + tag + " ");
        if (start == -1) return null;
        start = xml.indexOf(">", start) + 1;
        int end = xml.indexOf("</" + tag + ">", start);
        if (end == -1) return null;
        return xml.substring(start, end).trim();
    }

    private String extractXmlValue(String xml, String tag, String key) {
        String tagContent = extractXmlTag(xml, tag);
        if (tagContent == null) return null;
        return extractDnComponent(tagContent, key);
    }

    private String extractLineValue(String text, String... keys) {
        for (String line : text.split("\n")) {
            for (String key : keys) {
                if (line.startsWith(key)) return line.substring(key.length()).trim();
            }
        }
        return null;
    }

    private byte[] readFully(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private byte[] sha1(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(data);
    }

    private byte[] md5(byte[] data) throws Exception {
        return MessageDigest.getInstance("MD5").digest(data);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private SignatureResult error(String msg) {
        return new SignatureResult(SignatureStatus.ERROR, null, null, null, null, msg, null);
    }
}
