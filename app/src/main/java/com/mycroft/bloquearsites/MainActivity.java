package com.mycroft.bloquearsites;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MainActivity extends Activity {
    private BlockedSitesStore store;
    private ArrayAdapter<String> adapter;
    private final List<String> domains = new ArrayList<>();

    private TextView statusView;
    private TextView emptyView;
    private TextView browsersView;
    private EditText siteInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new BlockedSitesStore(this);
        setContentView(buildContentView());
        refreshSites();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityStatus();
        refreshSites();
        refreshBrowsers();
    }

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(250, 250, 250));

        TextView title = new TextView(this);
        title.setText("Bloquear Sites");
        title.setTextSize(28f);
        title.setTextColor(Color.rgb(25, 25, 25));
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Adicione um domínio. O bloqueio vale também para os subdomínios.");
        subtitle.setTextSize(15f);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, 0, 0, dp(16));
        root.addView(subtitle, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(15f);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(statusView, matchWrap());

        Button accessibilityButton = new Button(this);
        accessibilityButton.setText("Ativar acessibilidade");
        accessibilityButton.setAllCaps(false);
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.setMargins(0, dp(10), 0, dp(18));
        root.addView(accessibilityButton, buttonParams);
        accessibilityButton.setOnClickListener(v -> showAccessibilityDisclosure());

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);

        siteInput = new EditText(this);
        siteInput.setHint("exemplo.com");
        siteInput.setSingleLine(true);
        siteInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        siteInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        addRow.addView(siteInput, inputParams);

        Button addButton = new Button(this);
        addButton.setText("Adicionar");
        addButton.setAllCaps(false);
        LinearLayout.LayoutParams addButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        addButtonParams.setMargins(dp(8), 0, 0, 0);
        addRow.addView(addButton, addButtonParams);
        root.addView(addRow, matchWrap());

        addButton.setOnClickListener(v -> addSite());
        siteInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addSite();
                return true;
            }
            return false;
        });

        TextView listTitle = new TextView(this);
        listTitle.setText("Sites bloqueados");
        listTitle.setTextSize(18f);
        listTitle.setTextColor(Color.rgb(35, 35, 35));
        listTitle.setPadding(0, dp(22), 0, dp(8));
        root.addView(listTitle, matchWrap());

        emptyView = new TextView(this);
        emptyView.setText("Nenhum site adicionado.");
        emptyView.setTextColor(Color.GRAY);
        emptyView.setPadding(0, dp(12), 0, dp(12));
        root.addView(emptyView, matchWrap());

        ListView listView = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, domains);
        listView.setAdapter(adapter);
        listView.setDividerHeight(1);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String domain = domains.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("Remover bloqueio")
                    .setMessage("Remover " + domain + " da lista?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Remover", (dialog, which) -> {
                        store.remove(domain);
                        refreshSites();
                    })
                    .show();
        });

        TextView browsersTitle = new TextView(this);
        browsersTitle.setText("Navegadores encontrados");
        browsersTitle.setTextSize(18f);
        browsersTitle.setTextColor(Color.rgb(35, 35, 35));
        browsersTitle.setPadding(0, dp(18), 0, dp(6));
        root.addView(browsersTitle, matchWrap());

        browsersView = new TextView(this);
        browsersView.setTextSize(14f);
        browsersView.setTextColor(Color.DKGRAY);
        browsersView.setLineSpacing(0f, 1.15f);
        root.addView(browsersView, matchWrap());

        TextView footer = new TextView(this);
        footer.setText("O app não usa permissão de internet. A leitura da barra de endereço acontece localmente no aparelho.");
        footer.setTextSize(12f);
        footer.setTextColor(Color.GRAY);
        footer.setPadding(0, dp(12), 0, 0);
        root.addView(footer, matchWrap());

        return root;
    }

    private void addSite() {
        String raw = siteInput.getText().toString();
        String normalized = store.add(raw);

        if (normalized == null) {
            Toast.makeText(this, "Digite um domínio ou URL válido.", Toast.LENGTH_SHORT).show();
            return;
        }

        siteInput.setText("");
        refreshSites();
        Toast.makeText(this, normalized + " foi bloqueado.", Toast.LENGTH_SHORT).show();
    }

    private void refreshSites() {
        if (store == null || adapter == null) return;
        domains.clear();
        domains.addAll(store.getSortedList());
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(domains.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void refreshBrowsers() {
        if (browsersView == null) return;

        IdentifiedBrowsers.load(this);
        BrowserDetector detector = new BrowserDetector(this);
        List<String> lines = new ArrayList<>();
        for (String packageName : detector.installedBrowsers()) {
            BrowserProfile profile = BrowserProfiles.forPackage(packageName);
            String status;
            if (profile == null) {
                status = "verificado ao abrir: compatível se o app conseguir ler a barra;"
                        + " senão, fechado enquanto houver sites bloqueados";
            } else if (BrowserProfiles.isIdentified(packageName)) {
                status = "compatível (reconhecido como " + profile.getFamily() + ")";
            } else if (profile.getNote() != null) {
                status = "compatível (" + profile.getNote() + ")";
            } else {
                status = "compatível";
            }
            lines.add("\u2022 " + detector.labelOf(packageName) + " \u2014 " + status);
        }
        Collections.sort(lines, String.CASE_INSENSITIVE_ORDER);

        browsersView.setText(lines.isEmpty()
                ? "Nenhum navegador encontrado."
                : TextUtils.join("\n", lines));
    }

    private void showAccessibilityDisclosure() {
        new AlertDialog.Builder(this)
                .setTitle("Uso do serviço de acessibilidade")
                .setMessage(
                        "Para bloquear os sites que você escolher, o app precisa usar o serviço de acessibilidade para ler o texto visível da barra de endereço dos navegadores e identificar o domínio aberto.\n\n"
                                + "A URL é comparada somente no aparelho com a sua lista de bloqueio. O app não possui permissão de internet, não envia URLs, histórico ou a lista de sites a terceiros e não altera configurações sem sua ação.\n\n"
                                + "Quando um domínio bloqueado é detectado, o app cobre a tela e leva o navegador para o Google: toca na barra de endereço, digita google.com e confirma, trocando o site da aba atual. Se não conseguir, abre o Google em uma aba nova.\n\n"
                                + "Enquanto houver sites na lista, navegadores sem suporte são fechados ao abrir uma página, voltando para a tela inicial."
                )
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Concordo", (dialog, which) -> openAccessibilitySettings())
                .show();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Não foi possível abrir as configurações de acessibilidade.", Toast.LENGTH_LONG).show();
        }
    }

    private void updateAccessibilityStatus() {
        boolean enabled = isServiceEnabled();
        statusView.setText(enabled ? "Serviço ativo — bloqueio ligado" : "Serviço inativo — ative para bloquear");
        statusView.setTextColor(enabled ? Color.rgb(0, 105, 62) : Color.rgb(170, 70, 0));
        statusView.setBackgroundColor(enabled ? Color.rgb(226, 244, 234) : Color.rgb(255, 239, 220));
    }

    private boolean isServiceEnabled() {
        ComponentName expected = new ComponentName(this, SiteBlockAccessibilityService.class);
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null) return false;

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            ComponentName enabled = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(enabled)) return true;
        }
        return false;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
