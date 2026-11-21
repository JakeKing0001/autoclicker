package com.yarnloop.bot;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Schermata iniziale: istruzioni e impostazioni. */
public class MainActivity extends Activity {

    static final String PREFS = "settings";

    private TextView serviceState;
    private EditText tapDelay, maxBelt;
    private CheckBox dryRun;

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(LinearLayout parent, String s, float size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setPadding(0, dp(6), 0, dp(6));
        parent.addView(t);
        return t;
    }

    private Button button(LinearLayout parent, String s, Runnable r) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        parent.addView(b);
        return b;
    }

    private EditText number(LinearLayout parent, String label, int value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setMinWidth(dp(90));
        row.addView(e);
        parent.addView(row);
        return e;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(24), dp(18), dp(24));

        text(col, "Yarn Loop Bot", 26);
        serviceState = text(col, "", 16);

        text(col, "1. Attiva il servizio \"Yarn Loop Bot\" nelle impostazioni di Accessibilità.", 15);
        button(col, "Apri impostazioni Accessibilità",
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        text(col, "Se l'interruttore è grigio (\"impostazione con limitazioni\"): apri Info app, "
                + "tocca i tre puntini ⋮ in alto a destra → \"Consenti impostazioni con limitazioni\", "
                + "poi riprova.", 13);
        button(col, "Apri Info app", () -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));

        text(col, "2. Apri Yarn Loop e avvia un livello. In alto a sinistra compare un pulsante:\n"
                + "  ▶  avvia il bot      ■  lo ferma\n"
                + "  ✥  trascinalo per spostare il pulsante\n"
                + "  tieni premuto ▶  salva uno screenshot in Immagini/YarnLoopBot "
                + "(utile per segnalare problemi)", 15);

        text(col, "Impostazioni", 20);
        tapDelay = number(col, "Pausa dopo ogni raffica di tocchi (ms)", p.getInt("after_burst_ms", 500));
        maxBelt = number(col, "Massimo rocchetti sul nastro", p.getInt("max_belt", 5));
        dryRun = new CheckBox(this);
        dryRun.setText("Solo prova: mostra le mosse senza toccare");
        dryRun.setChecked(p.getBoolean("dry_run", false));
        col.addView(dryRun);
        text(col, "Le impostazioni valgono dal prossimo ▶.", 12).setTextColor(Color.GRAY);

        text(col, "Il bot tocca solo i rocchetti (slot e griglia), mai i bonus a pagamento. "
                + "Si mette in pausa se esci dal gioco. Richiede Android 11 o superiore.", 12)
                .setTextColor(Color.GRAY);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = BotService.instance != null;
        serviceState.setText(on ? "✅ Servizio attivo" : "❌ Servizio non attivo");
        serviceState.setTextColor(on ? 0xFF2E7D32 : 0xFFC62828);
    }

    @Override
    protected void onPause() {
        super.onPause();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("after_burst_ms", parse(tapDelay, 500, 100, 10000))
                .putInt("max_belt", parse(maxBelt, 5, 1, 20))
                .putBoolean("dry_run", dryRun.isChecked())
                .apply();
    }

    private static int parse(EditText e, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(e.getText().toString().trim())));
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
