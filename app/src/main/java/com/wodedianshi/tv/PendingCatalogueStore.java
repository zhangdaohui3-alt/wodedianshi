package com.wodedianshi.tv;

import android.content.Context;
import android.util.AtomicFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Atomic, app-private staging for a background source-update candidate.
 * The large candidate JSON must not be stored in SharedPreferences' XML file.
 */
public final class PendingCatalogueStore {
    private static final String FILE_NAME = "pending_source_update.json";

    private PendingCatalogueStore() {}

    private static AtomicFile atomicFile(Context context) {
        File target = new File(context.getFilesDir(), FILE_NAME);
        return new AtomicFile(target);
    }

    public static void write(Context context, String json) throws IOException {
        AtomicFile file = atomicFile(context);
        FileOutputStream stream = file.startWrite();
        try {
            Writer writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
            writer.write(json == null ? "[]" : json);
            writer.flush();
            file.finishWrite(stream);
        } catch (IOException failure) {
            file.failWrite(stream);
            throw failure;
        } catch (RuntimeException failure) {
            file.failWrite(stream);
            throw failure;
        }
    }

    public static String read(Context context) throws IOException {
        AtomicFile file = atomicFile(context);
        try (FileInputStream stream = file.openRead();
             InputStreamReader input = new InputStreamReader(stream, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(input)) {
            StringBuilder json = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                json.append(buffer, 0, count);
            }
            return json.toString();
        } catch (FileNotFoundException missing) {
            return null;
        }
    }

    public static void delete(Context context) {
        atomicFile(context).delete();
    }
}
