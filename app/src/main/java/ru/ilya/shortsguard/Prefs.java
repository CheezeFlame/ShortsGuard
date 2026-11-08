package ru.ilya.shortsguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Настройки, общие для экрана приложения и службы. */
final class Prefs {
    static final String YT = "com.google.android.youtube";

    /**
     * Идентификаторы элементов экрана Shorts в YouTube.
     * НЕ ПРОВЕРЕНЫ на актуальной версии YouTube — уточняются режимом отладки
     * и правятся прямо в приложении, без пересборки.
     */
    static final String DEFAULT_IDS =
            "reel_recycler, reel_watch_player, reel_player_page_container, "
            + "reel_watch_fragment_root, reel_player_underlay";

    private static final String FILE = "guard";
    private static final String K_IDS = "ids";
    private static final String K_DEBUG = "debug";
    private static final String K_DUMP = "dump";
    private static final String K_CONSENT = "consent";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String idsRaw(Context c) {
        return sp(c).getString(K_IDS, DEFAULT_IDS);
    }

    /** Разобранный список; пересчитывается только при сохранении. Экран и служба — в одном процессе. */
    private static volatile List<String> cachedIds;

    static void setIds(Context c, String raw) {
        sp(c).edit().putString(K_IDS, raw).apply();
        cachedIds = parse(raw);
    }

    /** Полные имена вида com.google.android.youtube:id/reel_recycler */
    static List<String> ids(Context c) {
        List<String> l = cachedIds;
        if (l == null) {
            l = parse(idsRaw(c));
            cachedIds = l;
        }
        return l;
    }

    private static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw.split("[,\\s]+")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            out.add(s.contains(":id/") ? s : YT + ":id/" + s);
        }
        return Collections.unmodifiableList(out);
    }

    /** Согласие с описанием работы службы. Без него служба ничего не делает. */
    private static volatile Boolean cachedConsent;

    static boolean consented(Context c) {
        Boolean v = cachedConsent;
        if (v == null) {
            v = sp(c).getBoolean(K_CONSENT, false);
            cachedConsent = v;
        }
        return v;
    }

    static void setConsent(Context c, boolean v) {
        sp(c).edit().putBoolean(K_CONSENT, v).apply();
        cachedConsent = v;
    }

    static boolean debug(Context c) {
        return sp(c).getBoolean(K_DEBUG, false);
    }

    static void setDebug(Context c, boolean v) {
        sp(c).edit().putBoolean(K_DEBUG, v).apply();
    }

    static String dump(Context c) {
        return sp(c).getString(K_DUMP, "");
    }

    static void setDump(Context c, String v) {
        sp(c).edit().putString(K_DUMP, v).apply();
    }
}
