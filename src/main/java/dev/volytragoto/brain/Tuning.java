package dev.volytragoto.brain;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The numbers behind the flight logic that have no setting in the Meteor menu. Every place that uses one names it and
 * gives its default, e.g. {@code Tuning.d("lava.margin", 1.2)}; TUNING.md lists them all.
 *
 * To try another value without rebuilding, put {@code key = value} lines in {@code config/volytragoto.properties}
 * (it doesn't exist unless you create it). The file is read again when it changes.
 */
final class Tuning {
    private static final Logger LOG = LoggerFactory.getLogger("VolytraGoto");
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("volytragoto.properties");

    private static final Properties props = new Properties();
    private static long stamp = Long.MIN_VALUE, lastCheck;

    private Tuning() {
    }

    static double d(String key, double def) {
        String v = get(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        }
        catch (NumberFormatException e) {
            return def;
        }
    }

    static int i(String key, int def) {
        return (int) Math.round(d(key, def));
    }

    static boolean b(String key, boolean def) {
        String v = get(key);
        if (v == null) return def;
        v = v.trim();
        return v.equalsIgnoreCase("true") || v.equals("1");
    }

    private static String get(String key) {
        check();
        return props.isEmpty() ? null : props.getProperty(key);
    }

    private static void check() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 1000) return;
        lastCheck = now;
        try {
            if (!Files.isRegularFile(FILE)) {
                if (!props.isEmpty()) props.clear();
                stamp = Long.MIN_VALUE;
                return;
            }
            long st = Files.getLastModifiedTime(FILE).toMillis() * 31 + Files.size(FILE);
            if (st == stamp) return;
            stamp = st;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(FILE)) {
                p.load(r);
            }
            props.clear();
            props.putAll(p);
            LOG.info("[VG] {} value(s) read from {}", p.size(), FILE.getFileName());
        }
        catch (IOException e) {
            LOG.warn("[VG] couldn't read {}: {}", FILE.getFileName(), e.toString());
        }
    }
}
