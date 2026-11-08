package ru.ilya.shortsguard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Главный экран: выключатель и статус. Шаги настройки видны, пока настройка не завершена;
 * тема, сообщение о проблеме и отладка спрятаны внизу.
 */
public class MainActivity extends Activity {

    private static final int GREY = 0xFF888888;
    private int green, red;
    private int pad;
    private final List<Runnable> refreshers = new ArrayList<>();

    private static final String DISCLOSURE =
            "Shorts Guard использует службу специальных возможностей, чтобы:\n"
            + "• видеть название приложения на экране — понять, что YouTube открыт по ссылке;\n"
            + "• читать устройство экрана YouTube — распознать Shorts;\n"
            + "• нажимать «Назад» на запрещённом Shorts.\n\n"
            + "Содержимое других приложений не читается, данные не сохраняются и не отправляются. "
            + "Доступа в интернет у приложения нет. Код открыт: github.com/CheezeFlame/ShortsGuard";

    @Override
    protected void onCreate(Bundle b) {
        boolean dark = applyTheme();
        super.onCreate(b);
        green = dark ? 0xFF81C784 : 0xFF2E7D32;
        red = dark ? 0xFFEF9A9A : 0xFFC62828;
        pad = dp(20);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        col.addView(text("Shorts Guard", 26, true));
        buildMainSwitch(col);

        // Настройка: открыта, пока не завершена
        LinearLayout setup = section();
        buildSetup(setup);
        Button setupToggle = linkButton("Настройка");
        setupToggle.setOnClickListener(v -> toggle(setup));
        col.addView(spacer(24));
        col.addView(setupToggle);
        col.addView(setup);
        refreshers.add(() -> {
            boolean ready = isReady();
            setupToggle.setVisibility(ready ? View.VISIBLE : View.GONE);
            if (!ready) setup.setVisibility(View.VISIBLE);
        });
        setup.setVisibility(isReady() ? View.GONE : View.VISIBLE);

        // Нижние ссылки
        LinearLayout report = section();
        buildReport(report);
        LinearLayout adv = section();
        buildAdvanced(adv);

        col.addView(spacer(16));
        Button reportBtn = linkButton("Сообщить о проблеме");
        reportBtn.setOnClickListener(v -> toggle(report));
        col.addView(reportBtn);
        col.addView(report);

        String[] themeNames = {"как в системе", "светлая", "тёмная"};
        Button themeBtn = linkButton("Тема: " + themeNames[Prefs.theme(this)]);
        themeBtn.setOnClickListener(v -> {
            Prefs.setTheme(this, (Prefs.theme(this) + 1) % 3);
            recreate();
        });
        col.addView(themeBtn);

        Button advBtn = linkButton("Для отладки");
        advBtn.setOnClickListener(v -> toggle(adv));
        col.addView(advBtn);
        col.addView(adv);

        ScrollView sv = new ScrollView(this);
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Android 15 рисует приложение под системными панелями — отступаем на их высоту.
        sv.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(sv);
        setLightBars(!dark);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Runnable r : refreshers) r.run();
    }

    private void refreshAll() {
        for (Runnable r : refreshers) r.run();
    }

    // ---------- выключатель ----------

    private void buildMainSwitch(LinearLayout col) {
        Switch sw = new Switch(this);
        sw.setText("Блокировка Shorts");
        sw.setTextSize(20);
        sw.setPadding(0, dp(16), 0, dp(4));
        sw.setChecked(Prefs.enabled(this));
        sw.setOnCheckedChangeListener((v, on) -> {
            Prefs.setEnabled(this, on);
            refreshAll();
        });
        TextView status = text("", 15, false);
        col.addView(sw);
        col.addView(status);
        refreshers.add(() -> {
            if (!Prefs.enabled(this)) {
                status.setText("Выключена");
                status.setTextColor(GREY);
            } else if (!isReady()) {
                status.setText("Не работает — завершите настройку");
                status.setTextColor(red);
            } else {
                status.setText("Работает");
                status.setTextColor(green);
            }
        });
    }

    // ---------- настройка ----------

    private void buildSetup(LinearLayout s) {
        LinearLayout s1 = step(s, "1. Разрешить работу", () -> Prefs.consented(this));
        s1.addView(text(DISCLOSURE, 14, false));
        CheckBox consent = new CheckBox(this);
        consent.setText("Понятно, разрешаю");
        consent.setChecked(Prefs.consented(this));
        consent.setOnCheckedChangeListener((v, on) -> {
            Prefs.setConsent(this, on);
            refreshAll();
        });
        s1.addView(consent);

        LinearLayout s2 = step(s, "2. Включить службу", this::isServiceEnabled);
        s2.addView(text("«Спец. возможности» → «Установленные приложения» → Shorts Guard → включить. "
                + "Если появится «Доступ запрещён» или «Ограниченная настройка» — закройте окно, "
                + "откройте «О приложении» → ⋮ → «Разрешить ограниченные настройки» и повторите.", 14, false));
        s2.addView(button("Спец. возможности", () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        s2.addView(button("О приложении", () -> open(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)))));

        LinearLayout s3 = step(s, "3. Работа в фоне", this::isIgnoringBattery);
        s3.addView(text("Чтобы система не отключала службу.", 14, false));
        s3.addView(button("Разрешить", this::requestBatteryExemption));

        LinearLayout s4 = step(s, "4. Полка Shorts на главной YouTube", null);
        s4.addView(text("В YouTube: «Вы» → Настройки → Управление временем → Лимит ленты Shorts → 0 минут.", 14, false));
        s4.addView(button("Открыть YouTube", () -> {
            Intent yt = getPackageManager().getLaunchIntentForPackage(Prefs.YT);
            if (yt != null) open(yt); else toast("YouTube не найден");
        }));
    }

    // ---------- сообщение о проблеме ----------

    /**
     * Интернета у приложения нет, поэтому отчёт уходит через системное меню «Поделиться»:
     * человек сам выбирает Telegram, почту и т.п. и кому отправить.
     */
    private void buildReport(LinearLayout r) {
        r.addView(text("Опишите, что случилось. Модель телефона, версии Android и YouTube "
                + "добавятся автоматически.", 14, false));
        EditText msg = new EditText(this);
        msg.setHint("Например: вкладка Shorts открывается и не закрывается");
        msg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        msg.setMinLines(3);
        r.addView(msg);
        r.addView(button("Отправить", () -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Shorts Guard: проблема");
            send.putExtra(Intent.EXTRA_TEXT, buildReportText(msg.getText().toString()));
            open(Intent.createChooser(send, "Отправить отчёт"));
        }));
    }

    private String buildReportText(String userText) {
        StringBuilder sb = new StringBuilder();
        sb.append("Shorts Guard — отчёт о проблеме\n\n");
        sb.append(userText.trim().isEmpty() ? "(без описания)" : userText.trim()).append("\n\n");
        sb.append("Телефон: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Shorts Guard: ").append(versionOf(getPackageName())).append('\n');
        sb.append("YouTube: ").append(versionOf(Prefs.YT)).append('\n');
        sb.append("Согласие: ").append(Prefs.consented(this) ? "да" : "нет")
          .append(", служба: ").append(isServiceEnabled() ? "вкл" : "выкл")
          .append(", блокировка: ").append(Prefs.enabled(this) ? "вкл" : "выкл")
          .append(", фон: ").append(isIgnoringBattery() ? "разрешён" : "нет").append('\n');
        sb.append("id: ").append(Prefs.idsRaw(this)).append('\n');
        String dump = Prefs.dump(this);
        if (!dump.isEmpty()) sb.append("\nОтладка:\n").append(dump).append('\n');
        return sb.toString();
    }

    private String versionOf(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "не установлен";
        }
    }

    // ---------- отладка ----------

    private void buildAdvanced(LinearLayout adv) {
        adv.addView(text("Если Shorts не закрываются: включите режим отладки, откройте любой Shorts, "
                + "вернитесь сюда и нажмите «Обновить». Скопируйте id со словом reel в список и сохраните.", 14, false));
        EditText ids = new EditText(this);
        ids.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        ids.setText(Prefs.idsRaw(this));
        adv.addView(ids);
        adv.addView(button("Сохранить список", () -> {
            Prefs.setIds(this, ids.getText().toString());
            toast("Сохранено");
        }));
        adv.addView(button("Сбросить список", () -> {
            Prefs.setIds(this, Prefs.DEFAULT_IDS);
            ids.setText(Prefs.DEFAULT_IDS);
        }));
        CheckBox debug = new CheckBox(this);
        debug.setText("Режим отладки");
        debug.setChecked(Prefs.debug(this));
        debug.setOnCheckedChangeListener((v, on) -> Prefs.setDebug(this, on));
        adv.addView(debug);
        TextView dump = text("", 13, false);
        dump.setTextIsSelectable(true);
        adv.addView(button("Обновить", () -> dump.setText(Prefs.dump(this))));
        adv.addView(dump);
        refreshers.add(() -> dump.setText(Prefs.dump(this)));
    }

    // ---------- элементы ----------

    /** Шаг с заголовком и отметкой; done == null — без автопроверки. */
    private LinearLayout step(LinearLayout parent, String title, BooleanSupplier done) {
        parent.addView(spacer(16));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 17, true);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (done != null) {
            TextView mark = text("", 15, true);
            head.addView(mark);
            refreshers.add(() -> {
                boolean ok = done.getAsBoolean();
                mark.setText(ok ? "✓" : "");
                mark.setTextColor(green);
            });
        }
        parent.addView(head);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        parent.addView(body);
        return body;
    }

    private LinearLayout section() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setVisibility(View.GONE);
        return l;
    }

    private void toggle(View v) {
        v.setVisibility(v.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** Плоская текстовая кнопка для второстепенных действий. */
    private Button linkButton(String label) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setMinimumHeight(dp(h));
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("Не удалось открыть");
        }
    }

    // ---------- тема ----------

    /** Ставит тему до создания экрана; возвращает true, если она тёмная. */
    private boolean applyTheme() {
        int t = Prefs.theme(this);
        if (t == Prefs.THEME_SYSTEM) {
            int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_YES; // AppTheme сам следует системе
        }
        boolean dark = t == Prefs.THEME_DARK;
        setTheme(dark ? android.R.style.Theme_DeviceDefault_NoActionBar
                      : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        return dark;
    }

    @SuppressWarnings("deprecation")
    private void setLightBars(boolean light) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            c.setSystemBarsAppearance(light ? mask : 0, mask);
        } else {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            d.setSystemUiVisibility(light ? (f | mask) : (f & ~mask));
        }
    }

    // ---------- проверки ----------

    private boolean isReady() {
        return Prefs.consented(this) && isServiceEnabled();
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, ShortsGuardService.class);
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me.flattenToString()) || s.equalsIgnoreCase(me.flattenToShortString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isIgnoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryExemption() {
        if (isIgnoringBattery()) {
            toast("Уже разрешено");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}
