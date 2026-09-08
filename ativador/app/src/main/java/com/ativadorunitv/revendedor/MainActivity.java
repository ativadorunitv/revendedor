package com.ativadorunitv.revendedor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public final class MainActivity extends Activity {
    private static final String IDS_JSON_URL = "https://ativadorunitv.github.io/revendedor/ids.json";
    private static final String UNITV_APK_URL = "https://ativadorunitv.github.io/revendedor/unitv-free/5.8.1.apk";
    private static final String DRIVE_PREFIX = "https://drive.google.com/uc?export=download&id=";
    private static final String ACTIVATION_NAMESPACE = "unitv-activation-v1|U7vF-93aL-2026|";
    private static final int REQUEST_STORAGE = 41;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private EditText activationInput;
    private TextView statusText;
    private Button activateButton;
    private Button cleanupButton;
    private String currentPage = "";
    private String deviceCode;
    private volatile boolean busy;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(4, 14, 9));
        window.setNavigationBarColor(Color.rgb(4, 14, 9));
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        root = new FrameLayout(this);
        root.setBackground(screenBackground());
        setContentView(root);
        deviceCode = createDeviceCode();
        root.postDelayed(this::refreshPermissionFlow, 180L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (root != null) {
            root.postDelayed(this::refreshPermissionFlow, 240L);
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void refreshPermissionFlow() {
        if (!hasStorageAccess()) {
            showPermissionPage();
        } else {
            showMainPage();
        }
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent appPermission = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                if (appPermission.resolveActivity(getPackageManager()) != null) {
                    startActivity(appPermission);
                } else {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_STORAGE);
            }
        } catch (Exception error) {
            Toast.makeText(this, "Não foi possível abrir a permissão de arquivos.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE) {
            root.postDelayed(this::refreshPermissionFlow, 120L);
        }
    }

    private void showPermissionPage() {
        if ("permission".equals(currentPage)) {
            return;
        }
        currentPage = "permission";
        root.removeAllViews();

        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView symbol = text("▣", 42, Color.rgb(103, 233, 158), true);
        symbol.setGravity(Gravity.CENTER);
        card.addView(symbol, size(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        TextView eyebrow = text("ACESSO NECESSÁRIO", 13, Color.rgb(103, 233, 158), true);
        eyebrow.setGravity(Gravity.CENTER);
        card.addView(eyebrow, wrap());

        TextView title = text("Permissão de arquivos", 25, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, 0);
        card.addView(title, wrap());

        TextView description = text("O ativador precisa acessar os arquivos do aparelho para copiar e limpar a configuração.", 16, Color.rgb(185, 216, 198), false);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(dp(3), 1f);
        description.setPadding(0, dp(12), 0, dp(20));
        card.addView(description, wrap());

        Button allow = actionButton("Conceder permissão", true);
        allow.setOnClickListener(view -> requestStoragePermission());
        card.addView(allow, size(dp(260), dp(48)));

        addCenteredCard(card);
        allow.requestFocus();
    }

    private void showMainPage() {
        if ("main".equals(currentPage)) {
            return;
        }
        currentPage = "main";
        root.removeAllViews();

        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView brand = text("UniTV Free", 29, Color.WHITE, true);
        brand.setGravity(Gravity.CENTER);
        card.addView(brand, wrap());

        TextView subtitle = text("Ativador", 14, Color.rgb(132, 219, 169), true);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(2), 0, dp(18));
        card.addView(subtitle, wrap());

        TextView deviceLabel = text("CÓDIGO DESTE DISPOSITIVO", 12, Color.rgb(152, 194, 169), true);
        deviceLabel.setGravity(Gravity.CENTER);
        card.addView(deviceLabel, wrap());

        TextView deviceValue = text(formatEight(deviceCode), 30, Color.WHITE, true);
        deviceValue.setGravity(Gravity.CENTER);
        deviceValue.setLetterSpacing(.12f);
        deviceValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        deviceValue.setBackground(round(Color.rgb(5, 27, 16), 12, Color.rgb(65, 143, 96), 1));
        deviceValue.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams deviceParams = size(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        deviceParams.bottomMargin = dp(8);
        card.addView(deviceValue, deviceParams);

        Button copyCode = actionButton("Copiar código", false);
        copyCode.setOnClickListener(view -> copyDeviceCode());
        LinearLayout.LayoutParams copyParams = size(dp(170), dp(38));
        copyParams.bottomMargin = dp(18);
        card.addView(copyCode, copyParams);

        TextView inputLabel = text("Senha de ativação", 14, Color.rgb(225, 246, 233), true);
        inputLabel.setGravity(Gravity.START);
        card.addView(inputLabel, size(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        activationInput = new EditText(this);
        activationInput.setSingleLine(true);
        activationInput.setTextColor(Color.WHITE);
        activationInput.setTextSize(22f);
        activationInput.setGravity(Gravity.CENTER);
        activationInput.setLetterSpacing(.16f);
        activationInput.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        activationInput.setHint("00000000");
        activationInput.setHintTextColor(Color.rgb(87, 125, 103));
        activationInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        activationInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        activationInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        activationInput.setBackground(round(Color.rgb(4, 23, 13), 11, Color.rgb(65, 143, 96), 1));
        card.addView(activationInput, size(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        activateButton = actionButton("Ativar", true);
        activateButton.setOnClickListener(view -> startActivation());
        LinearLayout.LayoutParams activateParams = size(dp(230), dp(46));
        activateParams.topMargin = dp(15);
        card.addView(activateButton, activateParams);

        cleanupButton = actionButton("Limpeza", false);
        cleanupButton.setOnClickListener(view -> confirmCleanup());
        LinearLayout.LayoutParams cleanupParams = size(dp(180), dp(40));
        cleanupParams.topMargin = dp(9);
        card.addView(cleanupButton, cleanupParams);

        statusText = text("Digite a senha gerada no site.", 14, Color.rgb(164, 207, 181), false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(16), 0, 0);
        card.addView(statusText, size(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addCenteredCard(card);
        activationInput.requestFocus();
    }

    private void startActivation() {
        if (busy) {
            return;
        }
        if (!hasStorageAccess()) {
            currentPage = "";
            showPermissionPage();
            return;
        }

        String entered = digitsOnly(activationInput.getText().toString());
        if (entered.length() != 8 || !activationCode(deviceCode).equals(entered)) {
            setStatus("Senha inválida. Confira os 8 números.", true);
            activationInput.selectAll();
            return;
        }

        setBusy(true);
        setStatus("Baixando e aplicando a configuração...", false);
        worker.execute(() -> {
            try {
                downloadAndActivateConfig();
                runOnUiThread(() -> setStatus("Ativado. Baixando UniTV Free 5.8.1...", false));
                File apk = downloadApk();
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("Ativação concluída. Abrindo o instalador...", false);
                    Toast.makeText(this, "UniTV Free ativado com sucesso.", Toast.LENGTH_LONG).show();
                    openPackageInstaller(apk);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus(friendlyError(error), true);
                });
            }
        });
    }

    private void downloadAndActivateConfig() throws Exception {
        List<String> ids = parseIds(readText(IDS_JSON_URL, 4 * 1024 * 1024));
        if (ids.isEmpty()) {
            throw new IOException("Nenhuma configuração foi encontrada no ids.json.");
        }
        String selectedId = ids.get(RANDOM.nextInt(ids.size()));
        byte[] config = readDriveFile(selectedId);
        if (config.length < 8) {
            throw new IOException("O arquivo de configuração recebido é inválido.");
        }
        File storage = Environment.getExternalStorageDirectory();
        writeBytes(new File(storage, ".config"), config);
        writeBytes(new File(storage, "Android/.config"), config);
    }

    private List<String> parseIds(String json) throws Exception {
        ArrayList<String> ids = new ArrayList<>();
        String trimmed = json.trim();
        JSONArray values = trimmed.startsWith("[")
                ? new JSONArray(trimmed)
                : new JSONObject(trimmed).getJSONArray("arquivos");
        for (int index = 0; index < values.length(); index++) {
            String id = values.optString(index, "").trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private byte[] readDriveFile(String id) throws Exception {
        String encodedId = URLEncoder.encode(id, "UTF-8");
        HttpURLConnection connection = open(DRIVE_PREFIX + encodedId);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode >= 400) {
                throw new IOException("Falha ao baixar a configuração.");
            }
            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.US).contains("text/html")) {
                String html = readString(connection.getInputStream(), 2 * 1024 * 1024);
                connection.disconnect();
                String confirmedUrl = "https://drive.usercontent.google.com/download?id=" + encodedId
                        + "&export=download&confirm=" + URLEncoder.encode(extractConfirm(html), "UTF-8");
                connection = open(confirmedUrl);
                if (connection.getResponseCode() >= 400) {
                    throw new IOException("Falha ao confirmar o download da configuração.");
                }
            }
            return readBytes(connection.getInputStream(), 5 * 1024 * 1024);
        } finally {
            connection.disconnect();
        }
    }

    private String extractConfirm(String html) {
        Matcher matcher = Pattern.compile("confirm=([^&\\\"']+)").matcher(html);
        return matcher.find() ? matcher.group(1) : "t";
    }

    private File downloadApk() throws Exception {
        File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            directory = getFilesDir();
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("A pasta de download não está disponível.");
        }
        File partial = new File(directory, "UnitvFree-5.8.1.apk.part");
        File output = new File(directory, "UnitvFree-5.8.1.apk");
        HttpURLConnection connection = open(UNITV_APK_URL);
        try {
            if (connection.getResponseCode() >= 400) {
                throw new IOException("Não foi possível baixar o UniTV Free.");
            }
            try (InputStream input = connection.getInputStream(); FileOutputStream stream = new FileOutputStream(partial, false)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download interrompido.");
                    }
                    stream.write(buffer, 0, read);
                }
                stream.getFD().sync();
            }
        } finally {
            connection.disconnect();
        }
        if (partial.length() < 1024 * 1024) {
            throw new IOException("O APK baixado é inválido.");
        }
        if (output.exists() && !output.delete()) {
            throw new IOException("Não foi possível substituir o APK anterior.");
        }
        if (!partial.renameTo(output)) {
            throw new IOException("Não foi possível finalizar o download.");
        }
        try (ZipFile zip = new ZipFile(output)) {
            if (zip.getEntry("AndroidManifest.xml") == null) {
                throw new IOException("O arquivo baixado não é um APK válido.");
            }
        }
        return output;
    }

    private void openPackageInstaller(File apk) {
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(ApkFileProvider.uriFor(apk.getName()), "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        } catch (Exception error) {
            setStatus("Ativado, mas não foi possível abrir o instalador.", true);
        }
    }

    private void confirmCleanup() {
        if (busy) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Limpeza")
                .setMessage("Deseja remover os arquivos de configuração deste aparelho?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpar", (dialog, which) -> runCleanup())
                .show();
    }

    private void runCleanup() {
        setBusy(true);
        setStatus("Removendo configurações...", false);
        worker.execute(() -> {
            File storage = Environment.getExternalStorageDirectory();
            boolean success = deleteRecursively(new File(storage, ".config"));
            success &= deleteRecursively(new File(storage, "Android/.config"));
            success &= deleteRecursively(new File(storage, ".properties"));
            success &= deleteRecursively(new File(storage, "Alarms/system_uf/google.wav"));
            boolean finalSuccess = success;
            runOnUiThread(() -> {
                setBusy(false);
                setStatus(finalSuccess ? "Limpeza concluída." : "Alguns arquivos não puderam ser removidos.", !finalSuccess);
            });
        });
    }

    private static boolean deleteRecursively(File file) {
        if (!file.exists()) {
            return true;
        }
        boolean success = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    success &= deleteRecursively(child);
                }
            }
        }
        return file.delete() && success;
    }

    private String createDeviceCode() {
        try {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.trim().isEmpty()) {
                androidId = getPreferences(MODE_PRIVATE).getString("device_seed", "");
                if (androidId.isEmpty()) {
                    androidId = Long.toHexString(RANDOM.nextLong()) + Long.toHexString(System.nanoTime());
                    getPreferences(MODE_PRIVATE).edit().putString("device_seed", androidId).apply();
                }
            }
            byte[] digest = sha256("unitv-device-v1|" + androidId);
            long value = unsignedFirstInt(digest) % 100_000_000L;
            return String.format(Locale.US, "%08d", value);
        } catch (Exception error) {
            return String.format(Locale.US, "%08d", Math.abs(RANDOM.nextInt()) % 100_000_000);
        }
    }

    private String activationCode(String code) {
        try {
            long value = unsignedFirstInt(sha256(ACTIVATION_NAMESPACE + code)) % 100_000_000L;
            return String.format(Locale.US, "%08d", value);
        } catch (Exception error) {
            return "";
        }
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long unsignedFirstInt(byte[] digest) {
        return ((long) (digest[0] & 0xff) << 24)
                | ((long) (digest[1] & 0xff) << 16)
                | ((long) (digest[2] & 0xff) << 8)
                | (long) (digest[3] & 0xff);
    }

    private static String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String formatEight(String digits) {
        return digits.substring(0, 4) + "-" + digits.substring(4, 8);
    }

    private void copyDeviceCode() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Código do dispositivo", deviceCode));
        Toast.makeText(this, "Código copiado.", Toast.LENGTH_SHORT).show();
    }

    private void setBusy(boolean value) {
        busy = value;
        if (activateButton != null) {
            activateButton.setEnabled(!value);
        }
        if (cleanupButton != null) {
            cleanupButton.setEnabled(!value);
        }
        if (activationInput != null) {
            activationInput.setEnabled(!value);
        }
    }

    private void setStatus(String message, boolean error) {
        if (statusText == null) {
            return;
        }
        statusText.setText(message);
        statusText.setTextColor(error ? Color.rgb(255, 164, 164) : Color.rgb(164, 224, 188));
    }

    private String friendlyError(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "Não foi possível concluir. Verifique a internet e tente novamente."
                : message;
    }

    private static HttpURLConnection open(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(18_000);
        connection.setReadTimeout(45_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AtivadorUnitvFree/1.0");
        connection.setRequestProperty("Accept", "*/*");
        return connection;
    }

    private static String readText(String address, int maximumBytes) throws Exception {
        HttpURLConnection connection = open(address);
        try {
            if (connection.getResponseCode() >= 400) {
                throw new IOException("Não foi possível ler o ids.json publicado.");
            }
            return readString(connection.getInputStream(), maximumBytes);
        } finally {
            connection.disconnect();
        }
    }

    private static String readString(InputStream input, int maximumBytes) throws IOException {
        return new String(readBytes(input, maximumBytes), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream input, int maximumBytes) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new IOException("Arquivo recebido é maior que o permitido.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void writeBytes(File destination, byte[] bytes) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta de configuração.");
        }
        File temporary = new File(destination.getAbsolutePath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Não foi possível substituir a configuração anterior.");
        }
        if (!temporary.renameTo(destination)) {
            throw new IOException("Não foi possível salvar a configuração.");
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(24));
        card.setBackground(round(Color.argb(244, 9, 39, 24), 20, Color.rgb(39, 105, 67), 1));
        return card;
    }

    private void addCenteredCard(LinearLayout card) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout stage = new LinearLayout(this);
        stage.setGravity(Gravity.CENTER);
        stage.setPadding(dp(20), dp(24), dp(20), dp(24));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(dp(560), Math.max(dp(280), screenWidth - dp(40)));
        stage.addView(card, size(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(stage, size(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private Button actionButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15f);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setFocusable(true);
        button.setClickable(true);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(buttonBackground(primary));
        button.setTextColor(buttonTextColors(primary));
        return button;
    }

    private StateListDrawable buttonBackground(boolean primary) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, round(Color.WHITE, 11, Color.rgb(169, 255, 205), 2));
        states.addState(new int[]{android.R.attr.state_pressed}, round(Color.rgb(151, 245, 189), 11, Color.TRANSPARENT, 0));
        states.addState(new int[]{-android.R.attr.state_enabled}, round(Color.rgb(48, 81, 62), 11, Color.TRANSPARENT, 0));
        states.addState(new int[]{}, primary
                ? round(Color.rgb(103, 233, 158), 11, Color.TRANSPARENT, 0)
                : round(Color.rgb(9, 48, 29), 11, Color.rgb(69, 153, 102), 1));
        return states;
    }

    private ColorStateList buttonTextColors(boolean primary) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_focused},
                new int[]{-android.R.attr.state_enabled},
                new int[]{}
        };
        int[] colors = new int[]{
                Color.rgb(5, 32, 19),
                Color.rgb(142, 166, 151),
                primary ? Color.rgb(5, 32, 19) : Color.rgb(183, 246, 207)
        };
        return new ColorStateList(states, colors);
    }

    private GradientDrawable screenBackground() {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(3, 12, 7), Color.rgb(8, 35, 21), Color.rgb(3, 12, 7)}
        );
        background.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return background;
    }

    private GradientDrawable round(int fillColor, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams wrap() {
        return size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
