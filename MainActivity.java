package com.udfviewer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_OPEN_FILE = 1001;

    private UdfDocumentView udfDocumentView;
    private View emptyStateLayout;
    private FloatingActionButton fabOpen;
    private Toolbar toolbar;
    private View rootView;

    private RecentFilesManager recentFilesManager;
    private SignatureVerifier signatureVerifier;
    private UdfPdfExporter pdfExporter;

    private UdfDocument currentDocument;
    private Uri currentUri;
    private String currentFileName;

    private View zoomIndicatorLayout;
    private TextView zoomIndicatorText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideZoomRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rootView = findViewById(android.R.id.content);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        udfDocumentView = findViewById(R.id.udfDocumentView);
        emptyStateLayout = findViewById(R.id.emptyStateLayout);
        fabOpen = findViewById(R.id.fabOpen);
        zoomIndicatorLayout = findViewById(R.id.zoomIndicatorLayout);
        zoomIndicatorText = findViewById(R.id.zoomIndicatorText);

        recentFilesManager = new RecentFilesManager(this);
        signatureVerifier = new SignatureVerifier(this);
        pdfExporter = new UdfPdfExporter(this);

        fabOpen.setOnClickListener(v -> openFilePicker());

        udfDocumentView.setOnZoomChangedListener(zoom ->
                showZoomIndicator((int)(zoom * 100)));

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            loadUdfFile(intent.getData());
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "UDF Dosyası Seç"), REQUEST_OPEN_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_FILE && resultCode == Activity.RESULT_OK && data != null) {
            loadUdfFile(data.getData());
        }
    }

    private void loadUdfFile(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}

        try {
            UdfParser parser = new UdfParser(this);
            UdfDocument document = parser.parse(uri);

            currentDocument = document;
            currentUri = uri;
            currentFileName = UdfUtils.getFileName(this, uri);
            if (currentFileName == null) currentFileName = "belge.udf";

            emptyStateLayout.setVisibility(View.GONE);
            udfDocumentView.setVisibility(View.VISIBLE);
            findViewById(R.id.documentFrame).setVisibility(View.VISIBLE);
            udfDocumentView.setDocument(document);

            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(currentFileName);
                getSupportActionBar().setSubtitle(document.getParagraphCount() + " paragraf");
            }

            recentFilesManager.addFile(uri, currentFileName);
            invalidateOptionsMenu();
            verifySignatureAsync(uri);

        } catch (Exception e) {
            Toast.makeText(this, "Dosya açılamadı: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void verifySignatureAsync(Uri uri) {
        new Thread(() -> {
            SignatureVerifier.SignatureResult result = signatureVerifier.verify(uri);
            runOnUiThread(() -> showSignatureStatus(result));
        }).start();
    }

    private void showSignatureStatus(SignatureVerifier.SignatureResult result) {
        if (result.status == SignatureVerifier.SignatureStatus.NO_SIGNATURE) return;
        String msg = result.getStatusLabel();
        if (result.signerName != null) msg += " — " + result.signerName;
        Snackbar snackbar = Snackbar.make(rootView, msg, Snackbar.LENGTH_LONG);
        snackbar.setBackgroundTint(result.getStatusColor());
        snackbar.setTextColor(0xFFFFFFFF);
        final String finalMsg = msg;
        snackbar.setAction("Detay", v -> showSignatureDialog(result));
        snackbar.setActionTextColor(0xFFFFFF99);
        snackbar.show();
    }

    private void showSignatureDialog(SignatureVerifier.SignatureResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Durum: ").append(result.getStatusLabel()).append("\n\n");
        if (result.rawSignatureType != null) sb.append("İmza Tipi: ").append(result.rawSignatureType).append("\n");
        if (result.signerName != null) sb.append("İmzalayan: ").append(result.signerName).append("\n");
        if (result.signerTitle != null) sb.append("Unvan: ").append(result.signerTitle).append("\n");
        if (result.signedAt != null) sb.append("Tarih: ").append(result.signedAt).append("\n");
        if (result.certificateInfo != null) sb.append("\nSertifika:\n").append(result.certificateInfo);
        if (result.errorMessage != null) sb.append("\nHata: ").append(result.errorMessage);
        new AlertDialog.Builder(this)
                .setTitle("Dijital İmza Bilgisi")
                .setMessage(sb.toString())
                .setPositiveButton("Tamam", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean hasDoc = currentDocument != null;
        menu.findItem(R.id.action_zoom_in).setVisible(hasDoc);
        menu.findItem(R.id.action_zoom_out).setVisible(hasDoc);
        menu.findItem(R.id.action_zoom_reset).setVisible(hasDoc);
        menu.findItem(R.id.action_copy_all).setVisible(hasDoc);
        menu.findItem(R.id.action_export_pdf).setVisible(hasDoc);
        menu.findItem(R.id.action_signature).setVisible(hasDoc);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open) { openFilePicker(); }
        else if (id == R.id.action_recent) { showRecentFiles(); }
        else if (id == R.id.action_copy_all) { copyAllText(); }
        else if (id == R.id.action_zoom_in) {
            udfDocumentView.zoomIn();
            showZoomIndicator((int)(udfDocumentView.getCurrentZoom() * 100));
        } else if (id == R.id.action_zoom_out) {
            udfDocumentView.zoomOut();
            showZoomIndicator((int)(udfDocumentView.getCurrentZoom() * 100));
        } else if (id == R.id.action_zoom_reset) {
            udfDocumentView.resetZoom();
            showZoomIndicator(100);
        } else if (id == R.id.action_export_pdf) {
            exportPdf();
        } else if (id == R.id.action_signature) {
            if (currentUri != null) {
                new Thread(() -> {
                    SignatureVerifier.SignatureResult r = signatureVerifier.verify(currentUri);
                    runOnUiThread(() -> showSignatureDialog(r));
                }).start();
            }
        } else if (id == R.id.action_night_mode) {
            toggleNightMode();
        }
        return true;
    }

    private void showRecentFiles() {
        List<RecentFilesManager.RecentFile> recents = recentFilesManager.getRecentFiles();
        if (recents.isEmpty()) {
            Toast.makeText(this, "Henüz dosya açılmadı", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayAdapter<RecentFilesManager.RecentFile> adapter =
                new ArrayAdapter<RecentFilesManager.RecentFile>(this,
                        android.R.layout.simple_list_item_2, android.R.id.text1, recents) {
                    @NonNull @Override
                    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                        View view = super.getView(position, convertView, parent);
                        TextView t1 = view.findViewById(android.R.id.text1);
                        TextView t2 = view.findViewById(android.R.id.text2);
                        t1.setText(recents.get(position).fileName);
                        t2.setText(recents.get(position).getFormattedDate());
                        return view;
                    }
                };
        new AlertDialog.Builder(this)
                .setTitle("Son Açılan Dosyalar")
                .setAdapter(adapter, (dialog, which) -> loadUdfFile(recents.get(which).getUri()))
                .setNeutralButton("Temizle", (d, w) -> {
                    recentFilesManager.clearAll();
                    Toast.makeText(this, "Geçmiş temizlendi", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void exportPdf() {
        if (currentDocument == null) return;
        Snackbar loading = Snackbar.make(rootView, "PDF oluşturuluyor...", Snackbar.LENGTH_INDEFINITE);
        loading.show();
        pdfExporter.export(currentDocument, currentFileName, new UdfPdfExporter.ExportCallback() {
            @Override
            public void onSuccess(File pdfFile) {
                runOnUiThread(() -> {
                    loading.dismiss();
                    Snackbar.make(rootView, "PDF hazır!", Snackbar.LENGTH_LONG)
                            .setAction("Paylaş", v -> startActivity(Intent.createChooser(
                                    UdfPdfExporter.createShareIntent(MainActivity.this, pdfFile),
                                    "PDF Paylaş")))
                            .show();
                });
            }
            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    loading.dismiss();
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void copyAllText() {
        String text = udfDocumentView.getAllText();
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, "Kopyalanacak metin yok", Toast.LENGTH_SHORT).show();
            return;
        }
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("UDF Belge Metni", text));
        Toast.makeText(this, "Tüm metin kopyalandı", Toast.LENGTH_SHORT).show();
    }

    private void toggleNightMode() {
        int cur = AppCompatDelegate.getDefaultNightMode();
        AppCompatDelegate.setDefaultNightMode(
                cur == AppCompatDelegate.MODE_NIGHT_YES
                        ? AppCompatDelegate.MODE_NIGHT_NO
                        : AppCompatDelegate.MODE_NIGHT_YES);
    }

    private void showZoomIndicator(int percent) {
        if (zoomIndicatorLayout == null || zoomIndicatorText == null) return;
        zoomIndicatorText.setText("%" + percent);
        zoomIndicatorLayout.setVisibility(View.VISIBLE);
        zoomIndicatorLayout.setAlpha(1f);
        if (hideZoomRunnable != null) handler.removeCallbacks(hideZoomRunnable);
        hideZoomRunnable = () -> zoomIndicatorLayout.animate().alpha(0f)
                .withEndAction(() -> zoomIndicatorLayout.setVisibility(View.GONE)).start();
        handler.postDelayed(hideZoomRunnable, 1500);
    }
}
