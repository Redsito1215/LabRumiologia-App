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
        
        // En Android moderno, mostrar PDF local en WebView requiere un truco de Google Drive 
        // o una librería. Como es un prototipo, cargaremos una URL de ejemplo o manejaremos la intención.
        if (path != null) {
            // Nota: Para archivos locales de assets, WebView no los renderiza directamente como PDF.
            // Esto es un placeholder funcional para la navegación.
            webView.loadUrl("file:///android_asset/" + path);
        }

        findViewById(R.id.btnPdfBack).setOnClickListener(v -> finish());
    }
}
