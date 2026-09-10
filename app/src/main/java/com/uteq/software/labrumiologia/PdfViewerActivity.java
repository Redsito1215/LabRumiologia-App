package com.uteq.software.labrumiologia;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class PdfViewerActivity extends AppCompatActivity {
    public static final String EXTRA_PDF_PATH = "pdf_path";
    public static final String EXTRA_PDF_TITLE = "pdf_title";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        String path = getIntent().getStringExtra(EXTRA_PDF_PATH);
        String title = getIntent().getStringExtra(EXTRA_PDF_TITLE);

        TextView titleView = findViewById(R.id.pdfTitle);
        titleView.setText(title != null ? title : "Visor de PDF");

        WebView webView = findViewById(R.id.pdfWebView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        
        webView.setWebViewClient(new WebViewClient());
        
        if (path != null) {
            webView.loadUrl("file:///android_asset/" + path);
        }

        findViewById(R.id.btnPdfBack).setOnClickListener(v -> finish());
    }
}
