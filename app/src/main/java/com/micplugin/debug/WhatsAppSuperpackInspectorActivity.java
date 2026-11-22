package com.micplugin.debug;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.facebook.superpack.AssetDecompressor;
import com.micplugin.R;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Read-only, on-device extraction helper for a WhatsApp ARM64 split selected
 * explicitly by the user. No WhatsApp files are changed and nothing is uploaded.
 */
public final class WhatsAppSuperpackInspectorActivity extends Activity {
    private static final int REQUEST_SPLIT = 6127;
    private static final String ENTRY_LIBS = "lib/arm64-v8a/libs.so";
    private static final String ENTRY_DECODER = "lib/arm64-v8a/libsuperpack.so";

    private static volatile String loadedDecoderPath;
    private static volatile String loadedDecoderSha256;

    private TextView status;
    private Button chooseButton;
    private Button shareButton;
    private File outputBundle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF101116);

        TextView title = new TextView(this);
        title.setText("WhatsApp native library inspector");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView description = new TextView(this);
        description.setText(
                "Select the matching split_config.arm64_v8a.apk from your own WhatsApp installation. "
                + "MicUp will extract the paired Superpack decoder and archive into private storage. "
                + "This does not modify WhatsApp or send files anywhere.");
        description.setTextColor(0xFFB9BBC5);
        description.setTextSize(14f);
        description.setPadding(0, dp(12), 0, dp(16));
        root.addView(description);

        chooseButton = new Button(this);
        chooseButton.setText("Choose ARM64 APK split and extract");
        chooseButton.setOnClickListener(v -> chooseSplit());
        root.addView(chooseButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        shareButton = new Button(this);
        shareButton.setText("Share analysis bundle");
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> shareBundle());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shareParams.topMargin = dp(8);
        root.addView(shareButton, shareParams);

        status = new TextView(this);
        status.setTextColor(0xFFE5E5EA);
        status.setTextSize(13f);
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(18), 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(8);
        root.addView(scroll, scrollParams);

        setContentView(root);
        status.setText("Ready. The helper requires an ARM64 (64-bit) Android process.");
    }

    private void chooseSplit() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_SPLIT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SPLIT || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }

        final Uri selected = data.getData();
        chooseButton.setEnabled(false);
        shareButton.setEnabled(false);
        outputBundle = null;
        status.setText("Reading the selected split and checking its ARM64 libraries…");

        Thread worker = new Thread(() -> runExtraction(selected), "MicUp-Superpack-Inspector");
        worker.setPriority(Thread.NORM_PRIORITY);
        worker.start();
    }

    private void runExtraction(Uri selectedUri) {
        File runRoot = new File(getFilesDir(),
                "whatsapp_native_inspector/run-" + System.currentTimeMillis());
        File inputDir = new File(runRoot, "input");
        File outputDir = new File(runRoot, "unpacked");

        try {
            if (!Process.is64Bit() || !hasArm64Abi()) {
                throw new IllegalStateException(
                        "This helper must run in a 64-bit ARM64 Android process.");
            }
            if (!inputDir.mkdirs() && !inputDir.isDirectory()) {
                throw new IllegalStateException("Cannot create private input directory.");
            }
            if (!outputDir.mkdirs() && !outputDir.isDirectory()) {
                throw new IllegalStateException("Cannot create private output directory.");
            }

            File libsArchive = new File(inputDir, "libs.so");
            File decoder = new File(inputDir, "libsuperpack.so");
            copySplitLibraries(selectedUri, libsArchive, decoder);

            int libsMachine = readElfMachine(libsArchive);
            int decoderMachine = readElfMachine(decoder);
            if (libsMachine != 183 || decoderMachine != 183) {
                throw new IllegalArgumentException(
                        "The selected files are not matching ELF64 AArch64 libraries "
                        + "(e_machine must be 183).");
            }

            String decoderHash = sha256(decoder);
            File runtimeDir = new File(getFilesDir(), "whatsapp_native_inspector/runtime");
            if (!runtimeDir.mkdirs() && !runtimeDir.isDirectory()) {
                throw new IllegalStateException("Cannot create private native runtime directory.");
            }
            File runtimeDecoder = new File(runtimeDir, "libsuperpack.so");
            if (loadedDecoderPath == null) {
                if (runtimeDecoder.exists() && !runtimeDecoder.setWritable(true, false)) {
                    throw new IllegalStateException("Cannot prepare the private decoder file for replacement.");
                }
                copyFile(decoder, runtimeDecoder);
                if (!runtimeDecoder.setReadOnly()) {
                    throw new IllegalStateException("Could not mark the native decoder read-only.");
                }
                String stablePath = runtimeDecoder.getCanonicalPath();
                System.load(stablePath);
                loadedDecoderPath = stablePath;
                loadedDecoderSha256 = decoderHash;
            } else if (!decoderHash.equals(loadedDecoderSha256)) {
                throw new IllegalStateException(
                        "A different Superpack decoder is already loaded in this MicUp process. "
                        + "Force-stop MicUp and reopen it before inspecting a different WhatsApp build.");
            }

            int nativeArchitecture = AssetDecompressor.get_architecture();
            String[] nativeResult = AssetDecompressor.decompress_from_so(
                    libsArchive.getCanonicalPath(),
                    outputDir.getCanonicalPath(),
                    "spo");

            List<File> extracted = new ArrayList<>();
            collectSoFiles(outputDir, extracted);
            Collections.sort(extracted, Comparator.comparing(File::getName));

            if (extracted.isEmpty()) {
                throw new IllegalStateException(
                        "The native decoder returned but no .so files were found. "
                        + "Returned value: " + java.util.Arrays.toString(nativeResult));
            }

            File bundle = createAnalysisBundle(outputDir, extracted, nativeArchitecture,
                    nativeResult);
            outputBundle = bundle;

            StringBuilder report = new StringBuilder();
            report.append("EXTRACTION COMPLETED\n");
            report.append("Native architecture code: ").append(nativeArchitecture).append("\n");
            report.append("Native return entries: ")
                    .append(nativeResult == null ? "null" : nativeResult.length).append("\n");
            report.append("Extracted shared libraries: ").append(extracted.size()).append("\n\n");
            for (File file : extracted) {
                report.append(file.getName())
                        .append("  ·  ")
                        .append(file.length())
                        .append(" bytes\n");
            }
            report.append("\nAnalysis bundle: ").append(bundle.getAbsolutePath());
            report.append("\nBundle size: ").append(bundle.length()).append(" bytes");
            report.append("\n\nNothing has been sent or uploaded. Use Share analysis bundle ")
                    .append("to send the ZIP back for static analysis.");

            publishStatus(report.toString(), true);
        } catch (Throwable error) {
            StringBuilder message = new StringBuilder("EXTRACTION FAILED\n");
            message.append(error.getClass().getSimpleName()).append(": ")
                    .append(error.getMessage() == null ? "(no message)" : error.getMessage());
            Throwable cause = error.getCause();
            int depth = 0;
            while (cause != null && depth++ < 4) {
                message.append("\nCaused by: ").append(cause.getClass().getSimpleName())
                        .append(": ").append(cause.getMessage());
                cause = cause.getCause();
            }
            publishStatus(message.toString(), false);
        }
    }

    private boolean hasArm64Abi() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    private void copyFile(File source, File destination) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination, false)) {
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.getFD().sync();
        }
    }

    private void copySplitLibraries(Uri uri, File libsArchive, File decoder) throws Exception {
        boolean foundArchive = false;
        boolean foundDecoder = false;
        InputStream source = getContentResolver().openInputStream(uri);
        if (source == null) {
            throw new IllegalArgumentException("Cannot read the selected APK split.");
        }

        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File destination = null;
                if (ENTRY_LIBS.equals(entry.getName())) {
                    destination = libsArchive;
                    foundArchive = true;
                } else if (ENTRY_DECODER.equals(entry.getName())) {
                    destination = decoder;
                    foundDecoder = true;
                }

                if (destination != null) {
                    try (FileOutputStream out = new FileOutputStream(destination)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            out.write(buffer, 0, count);
                        }
                        out.getFD().sync();
                    }
                }
                zip.closeEntry();
                if (foundArchive && foundDecoder) break;
            }
        }

        if (!foundArchive || !foundDecoder) {
            throw new IllegalArgumentException(
                    "The selected APK does not contain both expected entries: "
                    + ENTRY_LIBS + " and " + ENTRY_DECODER
                    + ". Choose the matching split_config.arm64_v8a.apk.");
        }
    }

    private int readElfMachine(File file) throws Exception {
        byte[] header = new byte[20];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int count = in.read(header, offset, header.length - offset);
                if (count < 0) throw new IllegalArgumentException("Truncated ELF file: " + file);
                offset += count;
            }
        }
        if (header[0] != 0x7f || header[1] != 'E'
                || header[2] != 'L' || header[3] != 'F'
                || header[4] != 2 || header[5] != 1) {
            throw new IllegalArgumentException("Not an ELF64 little-endian file: " + file.getName());
        }
        return (header[18] & 0xff) | ((header[19] & 0xff) << 8);
    }

    private void collectSoFiles(File directory, List<File> result) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectSoFiles(child, result);
            } else if (child.isFile() && child.getName().endsWith(".so")) {
                result.add(child);
            }
        }
    }

    private File createAnalysisBundle(File root, List<File> extracted,
            int nativeArchitecture, String[] nativeResult) throws Exception {
        File bundle = new File(getCacheDir(), "whatsapp-native-analysis.zip");
        List<File> selected = new ArrayList<>();
        for (File file : extracted) {
            String name = file.getName().toLowerCase(Locale.US);
            if (name.contains("whatsapp") || name.contains("opus")
                    || name.contains("ogg") || name.equals("libc++_shared.so")) {
                selected.add(file);
            }
        }
        if (selected.isEmpty()) selected.addAll(extracted);

        StringBuilder manifest = new StringBuilder();
        manifest.append("MicUp WhatsApp Superpack extraction\n");
        manifest.append("Native architecture code: ").append(nativeArchitecture).append("\n");
        manifest.append("Returned entries: ")
                .append(nativeResult == null ? "null" : java.util.Arrays.toString(nativeResult))
                .append("\n\n");
        for (File file : extracted) {
            manifest.append(relativePath(root, file)).append("\t")
                    .append(file.length()).append("\t")
                    .append(sha256(file)).append("\n");
        }

        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(bundle)))) {
            zip.putNextEntry(new ZipEntry("EXTRACTION_MANIFEST.txt"));
            zip.write(manifest.toString().getBytes("UTF-8"));
            zip.closeEntry();

            byte[] buffer = new byte[64 * 1024];
            for (File file : selected) {
                zip.putNextEntry(new ZipEntry(relativePath(root, file)));
                try (FileInputStream in = new FileInputStream(file)) {
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        zip.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
        return bundle;
    }

    private String relativePath(File root, File file) throws Exception {
        String base = root.getCanonicalPath() + File.separator;
        String path = file.getCanonicalPath();
        if (!path.startsWith(base)) throw new SecurityException("Unexpected extraction path.");
        return path.substring(base.length()).replace(File.separatorChar, '/');
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(Locale.US, "%02x", value));
        return hex.toString();
    }

    private void publishStatus(String message, boolean success) {
        runOnUiThread(() -> {
            status.setText(message);
            chooseButton.setEnabled(true);
            shareButton.setEnabled(success && outputBundle != null && outputBundle.isFile());
        });
    }

    private void shareBundle() {
        if (outputBundle == null || !outputBundle.isFile()) return;
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".provider", outputBundle);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share WhatsApp native analysis bundle"));
        } catch (Exception error) {
            status.append("\n\nShare error: " + error.getMessage());
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
