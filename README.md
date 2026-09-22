# Bloquear Sites

Aplicativo Android simples para bloquear domínios no navegador usando um `AccessibilityService`.

## Como funciona

1. O usuário adiciona um domínio, por exemplo `instagram.com`.
2. O app normaliza e salva apenas o domínio localmente em `SharedPreferences`.
3. Depois que o usuário consente e ativa manualmente o serviço nas Configurações de Acessibilidade do Android, o serviço observa mudanças da interface.
4. Para navegadores conhecidos, o app procura primeiro IDs específicos da barra de endereço.
5. Se o navegador não tiver um perfil conhecido ou o ID específico falhar, entra um fallback genérico que procura nós de acessibilidade cujo ID se parece com barra de URL/endereço.
6. A URL visível é normalizada para host e comparada com a lista. `example.com` também bloqueia `www.example.com` e `sub.example.com`, mas não bloqueia `evil-example.com`.
7. Ao detectar um domínio bloqueado, o serviço executa a ação global **Voltar** e mostra por um instante um banner de acessibilidade. Se a ação Voltar não estiver disponível, tenta ir para a tela inicial.

O app deliberadamente **não declara permissão de Internet**. A lista e as URLs lidas da interface permanecem no aparelho.

## Perfis incluídos

- Chrome e variantes Chromium: `url_bar`
- Brave: perfil Chromium + fallback genérico
- Microsoft Edge: perfil Chromium + fallback genérico
- Vivaldi/Kiwi: perfil Chromium + fallback genérico
- Firefox/Firefox Beta/Nightly/Tor Browser: `mozac_browser_toolbar_url_view` e `mozac_browser_toolbar_edit_url_view`
- Samsung Internet: `location_bar_edit_text`
- Opera/Opera Mini: `url_field`
- DuckDuckGo: `omnibarTextInput`
- Navegadores não reconhecidos: perfil genérico de alta confiança baseado no ID do nó

Os IDs específicos são heurísticas de implementação dos navegadores e podem mudar em atualizações. Por isso o perfil genérico é mantido como fallback.

## Segurança do matching

A comparação é feita no **host**, e não com `contains()`. Isso evita o erro clássico de considerar `evil-example.com` como se fosse `example.com`. O código também:

- remove `www.` na normalização;
- ignora páginas internas como `chrome://` e `about:`;
- trata portas e caminhos;
- converte domínios internacionalizados para ASCII/Punycode;
- bloqueia subdomínios por fronteira de label (`host.endsWith("." + dominio)`).

## Limitações importantes

Accessibility não fornece uma API oficial universal para obter a URL atual de qualquer navegador. A leitura depende do que cada navegador expõe na árvore de acessibilidade. Por isso:

- um navegador pode mudar o ID ou deixar de expor a URL;
- WebViews sem barra de endereço acessível não são bloqueados por este método;
- páginas internas do navegador são ignoradas;
- o modo anônimo funciona apenas quando a barra de endereço continua exposta à acessibilidade;
- o serviço reage a eventos da UI; há uma pequena janela entre a navegação e a ação de bloqueio.

Para bloqueio de rede independente da interface do navegador, a arquitetura adequada seria VPN local/DNS, que é outra abordagem e não foi adicionada aqui.

## Privacidade e Google Play

Este projeto define `isAccessibilityTool="false"`, porque bloqueio de sites por si só não deve ser declarado como ferramenta para pessoas com deficiência. A interface mostra uma divulgação antes de abrir as Configurações de Acessibilidade e exige a ação afirmativa **Concordo**.

Se o app for publicado no Google Play, revise a política vigente de AccessibilityService, preencha a declaração exigida e forneça as informações de privacidade/Data Safety aplicáveis.

## Fontes técnicas usadas

- Android AccessibilityService: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Configuração e flags de AccessibilityService: https://developer.android.com/reference/android/R.styleable
- Política do Google Play para AccessibilityService: https://support.google.com/googleplay/android-developer/answer/10964491
- Chromium `url_bar`: https://chromium.googlesource.com/chromium/src/
- Firefox Android / Android Components: https://searchfox.org/mozilla-mobile/source/firefox-android/

## Build

Requisitos locais:

- JDK 17
- Android SDK 35
- Gradle 8.10.2

Execute:

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

O APK de debug será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

O workflow `.github/workflows/android.yml` executa os testes, gera o APK de debug em cada push/PR e publica o arquivo como artifact `bloquearsites-debug` no GitHub Actions.
