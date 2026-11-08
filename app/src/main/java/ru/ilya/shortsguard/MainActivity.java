package ru.ilya.shortsguard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Мастер настройки: шаги с кнопками в нужные экраны и отметками о выполнении. */
public class MainActivity extends Activity {

    private static final int GREEN = 0xFF2E7D32;
    private static final int GREY = 0xFF888888;

    private static final String DISCLOSURE =
            "Shorts Guard использует службу специальных возможностей Android, чтобы:\n\n"
            + "• узнавать название приложения на экране — только чтобы понять, что YouTube "
            + "открыт по ссылке из другого приложения (например, Telegram);\n"
            + "• читать устройство экрана YouTube (идентификаторы элементов) — чтобы распознать "
            + "экран Shorts;\n"
            + "• нажимать «Назад», когда открыт запрещённый Shorts.\n\n"
            + "Чего приложение не делает: не читает содержимое других приложений, не сохраняет "
            + "и не отправляет никакие данные. У приложения нет разрешения на доступ в интернет — "
            + "отправить что-либо оно не может физически. Исходный код открыт: "
            + "github.com/CheezeFlame/ShortsGuard\n\n"
            + "Учтите: само разрешение Android на спец. возможности даёт право читать экран любого "
            + "приложения. Устанавливайте только из релизов этого репозитория.";

    private int pad;
    private final List<Runnable> refreshers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pad = dp(16);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = text("Shorts Guard", 24, true);
        col.addView(title);
        col.addView(text("Пройдите шаги по порядку. Отметка «✓ готово» появится сама.", 14, false));

        // Шаг 1. Согласие
        LinearLayout s1 = step(col, "1. Что делает приложение", () -> Prefs.consented(this));
        s1.addView(text(DISCLOSURE, 14, false));
        CheckBox consent = new CheckBox(this);
        consent.setText("Понятно, разрешаю работу службы");
        consent.setChecked(Prefs.consented(this));
        consent.setOnCheckedChangeListener((v, on) -> {
            Prefs.setConsent(this, on);
            refreshAll();
        });
        s1.addView(consent);

        // Шаг 2. Служба
        LinearLayout s2 = step(col, "2. Включить службу", this::isServiceEnabled);
        s2.addView(text("Нажмите «Спец. возможности» → «Установленные приложения» → Shorts Guard → включить.\n\n"
                + "Если появилось окно «Доступ для приложения запрещён» или «Ограниченная настройка»: "
                + "закройте его, нажмите «Сведения о приложении» → ⋮ в правом верхнем углу → "
                + "«Разрешить ограниченные настройки», затем снова «Спец. возможности».", 14, false));
        s2.addView(button("Спец. возможности", () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        s2.addView(button("Сведения о приложении", () -> open(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)))));

        // Шаг 3. Батарея
        LinearLayout s3 = step(col, "3. Не выгружать из памяти", this::isIgnoringBattery);
        s3.addView(text("Иначе система может отключать службу в фоне. Нажмите кнопку и разрешите. "
                + "На Samsung также проверьте, что Shorts Guard нет в списках «спящих» приложений "
                + "(Настройки → Аккумулятор).", 14, false));
        s3.addView(button("Разрешить работу в фоне", this::requestBatteryExemption));

        // Шаг 4. Полка Shorts
        LinearLayout s4 = step(col, "4. Убрать полку Shorts с главной YouTube", null);
        s4.addView(text("В YouTube: «Вы» → Настройки → Управление временем → Лимит ленты Shorts → 0 минут. "
                + "Shorts по ссылке всё равно откроется (если YouTube покажет окно лимита — нажмите "
                + "«Игнорировать»).", 14, false));
        s4.addView(button("Открыть YouTube", () -> {
            Intent yt = getPackageManager().getLaunchIntentForPackage(Prefs.YT);
            if (yt != null) open(yt); else toast("YouTube не найден");
        }));

        // Дополнительно (скрыто)
        LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);
        Button toggle = button("Дополнительно ▾", null);
        toggle.setOnClickListener(v -> {
            boolean show = adv.getVisibility() != View.VISIBLE;
            adv.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "Дополнительно ▴" : "Дополнительно ▾");
        });
        col.addView(spacer());
        col.addView(toggle);
        col.addView(adv);
        buildAdvanced(adv);

        ScrollView sv = new ScrollView(this);
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
    }

    /** Нужно, только если Shorts не распознаётся (другой телефон или новая версия YouTube). */
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
        debug.setText("Режим отладки (собирать id экрана YouTube)");
        debug.setChecked(Prefs.debug(this));
        debug.setOnCheckedChangeListener((v, on) -> Prefs.setDebug(this, on));
        adv.addView(debug);

        TextView dump = text("", 13, false);
        dump.setTextIsSelectable(true);
        adv.addView(button("Обновить данные отладки", () -> dump.setText(Prefs.dump(this))));
        adv.addView(dump);
        refreshers.add(() -> dump.setText(Prefs.dump(this)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void refreshAll() {
        for (Runnable r : refreshers) r.run();
    }

    // ---------- шаги и элементы ----------

    /** Заголовок шага со статусом; done == null — шаг без автоматической проверки. */
    private LinearLayout step(LinearLayout parent, String title, BooleanSupplier done) {
        parent.addView(spacer());
        TextView head = text(title, 18, true);
        parent.addView(head);
        if (done != null) {
            TextView status = text("", 14, true);
            parent.addView(status);
            refreshers.add(() -> {
                boolean ok = done.getAsBoolean();
                status.setText(ok ? "✓ готово" : "не выполнено");
                status.setTextColor(ok ? GREEN : GREY);
            });
        }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        parent.addView(body);
        return body;
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
        if (action != null) b.setOnClickListener(v -> action.run());
        return b;
    }

    private View spacer() {
        View v = new View(this);
        v.setMinimumHeight(dp(20));
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
            toast("Не удалось открыть экран настроек");
        }
    }

    // ---------- проверки ----------

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
