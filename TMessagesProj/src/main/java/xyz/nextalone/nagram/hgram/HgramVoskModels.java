package xyz.nextalone.nagram.hgram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** On-demand Vosk model storage: models are downloaded from alphacephei.com only when the user asks. */
public final class HgramVoskModels {

    public static final String LANG_RU = "ru";
    public static final String LANG_EN = "en";

    private static final String RU_FOLDER = "vosk-model-small-ru-0.22";
    private static final String EN_FOLDER = "vosk-model-small-en-us-0.15";
    private static final String BASE_URL = "https://alphacephei.com/vosk/models/";
    private static final ConcurrentHashMap<String, Integer> DOWNLOAD_PROGRESS = new ConcurrentHashMap<>();

    public interface Callback {
        void onProgress(int percent);

        void onDone(boolean success);
    }

    private HgramVoskModels() {
    }

    public static String folderName(String lang) {
        return LANG_RU.equals(lang) ? RU_FOLDER : EN_FOLDER;
    }

    public static int sizeMb(String lang) {
        return LANG_RU.equals(lang) ? 45 : 40;
    }

    private static File rootDir(String lang) {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "vosk/" + lang);
    }

    /** Directory that Vosk's Model() expects: contains am/, conf/, graph/. */
    public static File modelDir(String lang) {
        return new File(rootDir(lang), folderName(lang));
    }

    public static boolean isInstalled(String lang) {
        return isValidModel(modelDir(lang));
    }

    static boolean isValidModel(File dir) {
        return new File(dir, "am/final.mdl").length() > 0
                && new File(dir, "conf/mfcc.conf").length() > 0
                && (new File(dir, "graph/HCLG.fst").length() > 0
                    || new File(dir, "graph/HCLr.fst").length() > 0 && new File(dir, "graph/Gr.fst").length() > 0);
    }

    public static void delete(String lang) {
        if (DOWNLOAD_PROGRESS.containsKey(lang)) return;
        deleteRecursively(rootDir(lang));
    }

    /** Current percentage, or -1 when no download is in progress. */
    public static int downloadProgress(String lang) {
        Integer progress = DOWNLOAD_PROGRESS.get(lang);
        return progress == null ? -1 : progress;
    }

    /** Downloads and unpacks the model in the background; a non-null callback runs on the UI thread. */
    public static void download(String lang, Callback callback) {
        if (DOWNLOAD_PROGRESS.putIfAbsent(lang, 0) != null) {
            if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.onDone(false));
            return;
        }
        Thread thread = new Thread(() -> {
            File tmp = new File(ApplicationLoader.applicationContext.getCacheDir(), "vosk-" + lang + ".zip.part");
            File staging = new File(rootDir(lang).getParentFile(), lang + ".installing");
            HttpURLConnection connection = null;
            boolean ok = false;
            try {
                URL url = new URL(BASE_URL + folderName(lang) + ".zip");
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.connect();
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + connection.getResponseCode());
                }
                long total = HgramDownloads.contentLength(connection);
                long done = 0;
                int lastPercent = -1;
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        done += read;
                        if (total > 0) {
                            int percent = (int) Math.min(99, done * 100 / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                DOWNLOAD_PROGRESS.put(lang, percent);
                                if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.onProgress(percent));
                            }
                        }
                    }
                }
                connection.disconnect();

                deleteRecursively(staging);
                if (!staging.mkdirs() && !staging.isDirectory()) {
                    throw new IllegalStateException("mkdirs failed");
                }
                unzip(tmp, staging);
                if (!isValidModel(new File(staging, folderName(lang)))) {
                    throw new IllegalStateException("Incomplete model");
                }
                File root = rootDir(lang);
                deleteRecursively(root);
                if (!staging.renameTo(root)) throw new IllegalStateException("Cannot install model");
                ok = isInstalled(lang);
            } catch (Exception e) {
                ok = false;
            } finally {
                if (connection != null) connection.disconnect();
                deleteRecursively(staging);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                DOWNLOAD_PROGRESS.remove(lang);
            }
            boolean success = ok;
            if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.onDone(success));
        }, "HgramVoskDownload-" + lang);
        thread.start();
    }

    static void unzip(File zip, File target) throws Exception {
        String rootPath = target.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new java.io.FileInputStream(zip))) {
            final byte[] buffer = new byte[64 * 1024];
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                File out = new File(target, entry.getName());
                if (!out.getCanonicalPath().startsWith(rootPath)) {
                    throw new SecurityException("Zip entry outside target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (OutputStream os = new FileOutputStream(out)) {
                        int read;
                        while ((read = zin.read(buffer)) != -1) {
                            os.write(buffer, 0, read);
                        }
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
