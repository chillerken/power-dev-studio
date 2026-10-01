package online.luxwash.aicreatorstudio;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MainActivity extends Activity {
    private static final int CREATE_DOCUMENT_REQUEST = 1701;
    private WebView webView;
    private String pendingContent;
    private String pendingMime;
    private String pendingFilename;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);

        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("file".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme) || "blob".equalsIgnoreCase(scheme)) {
                    return false;
                }
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) { }
                    return true;
                }
                return true;
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private void requestSave(String filename, String mime, String content) {
        pendingFilename = filename == null || filename.trim().isEmpty() ? "export.txt" : filename;
        pendingMime = mime == null || mime.trim().isEmpty() ? "text/plain" : mime;
        pendingContent = content == null ? "" : content;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(pendingMime);
        intent.putExtra(Intent.EXTRA_TITLE, pendingFilename);
        startActivityForResult(intent, CREATE_DOCUMENT_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_DOCUMENT_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("Geen uitvoerstream");
            out.write((pendingContent == null ? "" : pendingContent).getBytes(StandardCharsets.UTF_8));
            out.flush();
            Toast.makeText(this, "Bestand opgeslagen", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Opslaan mislukt: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            pendingContent = null;
            pendingMime = null;
            pendingFilename = null;
        }
    }

    public class AndroidBridge {
        private final SecureRandom random = new SecureRandom();
        private static final int ITERATIONS = 120000;
        private static final int KEY_BITS = 256;

        @JavascriptInterface
        public String hashPassword(String password) {
            try {
                byte[] salt = new byte[16];
                random.nextBytes(salt);
                byte[] hash = derive(password == null ? "" : password, salt, ITERATIONS, KEY_BITS);
                return "pbkdf2$" + ITERATIONS + "$" +
                        Base64.encodeToString(salt, Base64.NO_WRAP) + "$" +
                        Base64.encodeToString(hash, Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean verifyPassword(String password, String stored) {
            try {
                if (stored == null) return false;
                String[] parts = stored.split("\\$");
                if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.decode(parts[2], Base64.NO_WRAP);
                byte[] expected = Base64.decode(parts[3], Base64.NO_WRAP);
                byte[] actual = derive(password == null ? "" : password, salt, iterations, expected.length * 8);
                return constantTimeEquals(expected, actual);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void saveTextFile(final String filename, final String mime, final String content) {
            runOnUiThread(() -> requestSave(filename, mime, content));
        }

        @JavascriptInterface
        public String platform() {
            return "Android native shell";
        }

        private byte[] derive(String password, byte[] salt, int iterations, int bits) throws Exception {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, bits);
            try {
                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                return factory.generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        }

        private boolean constantTimeEquals(byte[] a, byte[] b) {
            if (a == null || b == null || a.length != b.length) return false;
            int diff = 0;
            for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
            return diff == 0;
        }
    }
}
