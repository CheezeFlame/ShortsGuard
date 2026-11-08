package ru.ilya.shortsguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Логика:
 * 1. YouTube вышел на передний план из другого приложения (не лаунчера) —
 *    «взводим» разрешение на ARM_WINDOW_MS.
 * 2. Если в это окно появился экран Shorts — это переход по ссылке, Shorts разрешён.
 * 3. Shorts, появившийся без взвода (вкладка Shorts, полка, канал, похожие) — «Назад».
 * 4. В разрешённом Shorts любая прокрутка вертикальной ленты (свайп к следующему) — «Назад».
 * 5. Ушёл с экрана Shorts внутри YouTube — разрешение сгорает.
 *
 * Экономия: пока YouTube не на экране, служба слушает только смену окон;
 * события содержимого и прокрутки включаются, когда открыт YouTube.
 */
public class ShortsGuardService extends AccessibilityService {

    private static final long ARM_WINDOW_MS = 5000;   // сколько ждать появления Shorts после перехода из другого приложения
    private static final long GRACE_MS = 1500;        // первые мс разрешённого Shorts прокрутку игнорируем (стартовая разметка)
    private static final long BACK_COOLDOWN_MS = 900; // защита от двойного «Назад»
    private static final long FAST_CHECK_MS = 250;    // проверка после смены окна / «Назад»
    private static final long SLOW_CHECK_MS = 1000;   // проверка по изменению содержимого — не чаще раза в секунду
    private static final int MAX_BACK_IN_ROW = 3;     // чтобы случайно не выкинуть из YouTube совсем

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable checkRunnable = this::checkScreen;
    private boolean checkScheduled;
    private long checkDueAt;
    private long lastCheckAt;

    private static final int BASE_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
    private static final int YT_EVENTS = BASE_EVENTS
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SCROLLED;
    private boolean detailed; // подписаны ли на подробные события (только когда открыт YouTube)

    private final Set<String> transientPkgs = new HashSet<>(); // клавиатуры, шторка — не меняют «текущее приложение»
    private final Set<String> launcherPkgs = new HashSet<>();  // лаунчеры — переход из них не считается ссылкой

    private String frontPkg;          // последнее приложение на переднем плане
    private long armedUntil;
    private boolean shortsVisible;
    private boolean allowedSession;
    private long sessionStart;
    private long lastBack;
    private int backsInRow;
    private long lastDump;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        transientPkgs.add(getPackageName());
        transientPkgs.add("android");
        transientPkgs.add("com.android.systemui");
        transientPkgs.add("com.samsung.android.app.cocktailbarservice"); // Edge-панель Samsung
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            for (InputMethodInfo i : imm.getInputMethodList()) transientPkgs.add(i.getPackageName());
        }
        launcherPkgs.add("com.sec.android.app.launcher"); // One UI Home
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_ALL)) {
            launcherPkgs.add(r.activityInfo.packageName);
        }
        // Если служба включилась, когда YouTube уже открыт.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null
                && Prefs.YT.equals(root.getPackageName().toString())) {
            frontPkg = Prefs.YT;
            setDetailed(true);
            scheduleCheck(FAST_CHECK_MS);
        }
    }

    /** Включить/выключить подписку на частые события (содержимое, прокрутка). */
    private void setDetailed(boolean on) {
        if (detailed == on) return;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        info.eventTypes = on ? YT_EVENTS : BASE_EVENTS;
        setServiceInfo(info);
        detailed = on;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        int type = e.getEventType();
        long now = SystemClock.uptimeMillis();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (Prefs.YT.equals(pkg)) {
                boolean fromOtherApp = frontPkg != null
                        && !Prefs.YT.equals(frontPkg)
                        && !launcherPkgs.contains(frontPkg);
                if (fromOtherApp) armedUntil = now + ARM_WINDOW_MS;
                frontPkg = Prefs.YT;
                setDetailed(true);
            } else if (!transientPkgs.contains(pkg)) {
                frontPkg = pkg;
                setDetailed(false);
            }
        }

        if (!Prefs.YT.equals(pkg)) return;

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                && allowedSession && shortsVisible
                && now - sessionStart > GRACE_MS
                && isShortsPagerScroll(e)) {
            block("Только один Shorts по ссылке");
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleCheck(FAST_CHECK_MS);
        } else {
            // Изменение содержимого/прокрутка: не чаще раза в секунду.
            scheduleCheck(Math.max(FAST_CHECK_MS, SLOW_CHECK_MS - (now - lastCheckAt)));
        }
    }

    /** Запланировать проверку экрана; более ранняя заявка заменяет более позднюю. */
    private void scheduleCheck(long delay) {
        long due = SystemClock.uptimeMillis() + delay;
        if (checkScheduled) {
            if (due >= checkDueAt) return;
            handler.removeCallbacks(checkRunnable);
        }
        checkScheduled = true;
        checkDueAt = due;
        handler.postDelayed(checkRunnable, delay);
    }

    private void checkScreen() {
        checkScheduled = false;
        lastCheckAt = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        // Не YouTube (шторка, клавиатура, другое приложение) — состояние не трогаем.
        if (root == null || root.getPackageName() == null
                || !Prefs.YT.equals(root.getPackageName().toString())) return;

        long now = SystemClock.uptimeMillis();
        if (Prefs.debug(this) && now - lastDump > 1500) {
            lastDump = now;
            dumpIds(root);
        }

        boolean shorts = false;
        for (AccessibilityNodeInfo r : youtubeRoots(root)) {
            if (findShortsNode(r) != null) { shorts = true; break; }
        }
        boolean armed = now < armedUntil;

        if (shorts) {
            if (armed) {                 // пришли по ссылке (или вернулись из другого приложения)
                allowedSession = true;
                sessionStart = now;
                armedUntil = 0;
                backsInRow = 0;
            } else if (!shortsVisible) { // Shorts открыт изнутри YouTube
                allowedSession = false;
            }
            shortsVisible = true;
            if (!allowedSession) block("Shorts заблокированы");
        } else {
            shortsVisible = false;
            allowedSession = false;
            backsInRow = 0;
        }
    }

    /** Все окна YouTube: экран Shorts может быть под диалогом (например, «лимит Shorts»). */
    private List<AccessibilityNodeInfo> youtubeRoots(AccessibilityNodeInfo active) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        out.add(active);
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return out;
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null && r.getPackageName() != null
                    && Prefs.YT.equals(r.getPackageName().toString())) out.add(r);
        }
        return out;
    }

    private AccessibilityNodeInfo findShortsNode(AccessibilityNodeInfo root) {
        for (String id : Prefs.ids(this)) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                if (n != null && n.isVisibleToUser()) return n;
            }
        }
        return null;
    }

    /** Прокрутка именно вертикальной ленты Shorts, а не комментариев/описания. */
    private boolean isShortsPagerScroll(AccessibilityEvent e) {
        AccessibilityNodeInfo src = e.getSource();
        if (src == null) return false;
        String id = src.getViewIdResourceName();
        if (id != null && Prefs.ids(this).contains(id)) return true;

        CharSequence cls = src.getClassName();
        if (cls == null) return false;
        String c = cls.toString();
        if (!c.contains("RecyclerView") && !c.contains("ViewPager")) return false;

        // Запасной признак: полноэкранный прокручиваемый контейнер.
        Rect r = new Rect();
        src.getBoundsInScreen(r);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return r.height() >= dm.heightPixels * 0.85 && r.width() >= dm.widthPixels * 0.9;
    }

    private void block(String msg) {
        long now = SystemClock.uptimeMillis();
        allowedSession = false;
        if (now - lastBack < BACK_COOLDOWN_MS || backsInRow >= MAX_BACK_IN_ROW) return;
        lastBack = now;
        backsInRow++;
        performGlobalAction(GLOBAL_ACTION_BACK);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        // Проверить, ушли ли с Shorts; если нет — ещё «Назад» (не больше MAX_BACK_IN_ROW).
        handler.postDelayed(() -> scheduleCheck(FAST_CHECK_MS), BACK_COOLDOWN_MS + 50);
    }

    /** Режим отладки: собрать id элементов текущего экрана YouTube. */
    private void dumpIds(AccessibilityNodeInfo root) {
        Set<String> all = new TreeSet<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int visited = 0;
        while (!q.isEmpty() && visited < 4000) {
            AccessibilityNodeInfo n = q.poll();
            visited++;
            String id = n.getViewIdResourceName();
            if (id != null && id.startsWith(Prefs.YT + ":id/")) {
                all.add(id.substring((Prefs.YT + ":id/").length()));
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) q.add(ch);
            }
        }
        List<String> hot = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String s : all) {
            if (s.contains("reel") || s.contains("short")) hot.add(s); else rest.add(s);
        }
        String text = "Shorts найден по текущему списку: " + (findShortsNode(root) != null ? "ДА" : "нет")
                + "\n\nСодержат reel/short:\n" + String.join("\n", hot)
                + "\n\nОстальные:\n" + String.join(", ", rest);
        Prefs.setDump(this, text);
    }

    @Override
    public void onInterrupt() {}
}
