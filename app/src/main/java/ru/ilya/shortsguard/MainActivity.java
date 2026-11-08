package ru.ilya.shortsguard;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Экран настроек: включение службы, список id экрана Shorts, режим отладки. */
public class MainActivity extends Activity {

    private TextView status;
    private EditText ids;
    private CheckBox debug;
    private TextView dump;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(18);
        col.addView(status);

        Button open = new Button(this);
        open.setText("Открыть «Специальные возможности»");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        col.addView(open);

        TextView idsLabel = new TextView(this);
        idsLabel.setText("\nid элементов экрана Shorts (через запятую):");
        col.addView(idsLabel);

        ids = new EditText(this);
        ids.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        ids.setText(Prefs.idsRaw(this));
        col.addView(ids);

        Button save = new Button(this);
        save.setText("Сохранить список");
        save.setOnClickListener(v -> {
            Prefs.setIds(this, ids.getText().toString());
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
        });
        col.addView(save);

        Button reset = new Button(this);
        reset.setText("Сбросить к значениям по умолчанию");
        reset.setOnClickListener(v -> {
            Prefs.setIds(this, Prefs.DEFAULT_IDS);
            ids.setText(Prefs.DEFAULT_IDS);
        });
        col.addView(reset);

        debug = new CheckBox(this);
        debug.setText("Режим отладки (собирать id экрана YouTube)");
        debug.setChecked(Prefs.debug(this));
        debug.setOnCheckedChangeListener((v, on) -> Prefs.setDebug(this, on));
        col.addView(debug);

        Button refresh = new Button(this);
        refresh.setText("Обновить данные отладки");
        refresh.setOnClickListener(v -> dump.setText(Prefs.dump(this)));
        col.addView(refresh);

        dump = new TextView(this);
        dump.setTextIsSelectable(true);
        col.addView(dump);

        ScrollView sv = new ScrollView(this);
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText(isServiceEnabled() ? "Служба включена" : "Служба ВЫКЛЮЧЕНА");
        dump.setText(Prefs.dump(this));
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String me = new ComponentName(this, ShortsGuardService.class).flattenToString();
        String meShort = new ComponentName(this, ShortsGuardService.class).flattenToShortString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        }
        return false;
    }
}
