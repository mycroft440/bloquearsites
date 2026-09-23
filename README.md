# Bloquear Sites

Aplicativo Android simples para bloquear domínios no navegador usando um `AccessibilityService`.

## Como funciona

1. O usuário adiciona um domínio, por exemplo `instagram.com`.
2. O app normaliza e salva apenas o domínio localmente em `SharedPreferences`.
3. Depois que o usuário consente e ativa manualmente o serviço nas Configurações de Acessibilidade do Android, o serviço observa mudanças da interface.
4. Para navegadores conhecidos, o app usa o método da família do navegador (veja abaixo) para achar a barra de endereço.
5. Se o navegador não tiver uma família conhecida, entra um fallback genérico que procura nós de acessibilidade cujo ID se parece com barra de URL/endereço.
6. A URL visível é normalizada para host e comparada com a lista. `example.com` também bloqueia `www.example.com` e `sub.example.com`, mas não bloqueia `evil-example.com`.
7. Ao detectar um domínio bloqueado, o serviço cobre a tela por alguns instantes e leva o navegador para `google.com` na própria aba: toca na barra de endereço, digita o endereço, confere o texto e confirma com o Enter de acessibilidade (Android 11+). Se a barra não puder ser usada (Android 10 ou anterior, Custom Tabs, navegador não reconhecido ou barra não encontrada), o Google é aberto em uma aba nova (no Firefox, depois da ação **Voltar**).

O app deliberadamente **não declara permissão de Internet**. A lista e as URLs lidas da interface permanecem no aparelho.

## Famílias de navegadores

Navegadores que expõem a barra de endereço do mesmo jeito ficam na mesma família (`BrowserProfiles`), e cada família tem um método de identificação (`BrowserProfile.Method`). Uma diferença na forma de identificação pede uma família própria.

| Família | Pacotes | Método | Como a barra é achada |
|---|---|---|---|
| Chromium | Chrome (estável/Beta/Dev/Canary), Brave, Edge, Vivaldi, Kiwi | `VIEW_ID` | `url_bar` (EditText) |
| Firefox | Firefox, Firefox Beta, Nightly, Tor Browser | `FIREFOX_TOOLBAR` | barra em Jetpack Compose (`ADDRESSBAR_URL_BOX`, URL lida da descrição de acessibilidade) ou barras antigas em View (`mozac_browser_toolbar_url_view`, `url_bar_title`); só a URL exibida conta, confirmada por leituras estáveis |
| Samsung Internet | Samsung Internet e Beta | `VIEW_ID_WITH_REREAD` | `location_bar_edit_text` (UrlBar) e `compact_url_text` (barra compacta ao rolar); o domínio vem precedido da marca invisível U+200E |
| Mi Browser/AOSP | Mi Browser (`com.mi.globalbrowser`) e navegadores da base AOSP (`com.android.browser`, usado pela MIUI) | `VIEW_ID_WITH_REREAD` | `url` (UrlInputView), com a URL sem o esquema |
| Opera | Opera, Opera Beta, Opera Mini | `VIEW_ID` | `url_field` |
| DuckDuckGo | DuckDuckGo | `VIEW_ID` | `omnibarTextInput` |
| Via | `mark.via.gp`, `mark.via` | `TOOLBAR_STRUCTURE` | IDs ofuscados: TextView/EditText encostado no topo ou na base da janela, fora da página, com uma URL ou domínio inteiro |
| UC Browser | `com.UCMobile.intl`, `com.UCMobile` | `TOOLBAR_STRUCTURE` | barra exibida montada por código ofuscado; o campo de edição de endereço fica no topo |

- `VIEW_ID_WITH_REREAD` e `TOOLBAR_STRUCTURE` ouvem todos os eventos e releem a barra após um curto atraso quando a primeira leitura falha.
- **Via:** por padrão a barra mostra o título da página, e a URL não fica exposta. Para o bloqueio funcionar, nas configurações do Via mude **Conteúdo da caixa de URL** (em inglês, *URL field content*) de **Título** para **URL** ou **Domínio**. Com o título, só endereços digitados na barra são bloqueados.
- **UC Browser:** endereços digitados na barra são bloqueados. Páginas abertas por links só são reconhecidas se a barra do UC mostrar a URL ou o domínio; se ela mostrar o título da página, não há URL a ler.
- Os pacotes das famílias acima formam a lista de navegadores suportados.

## Navegadores sem suporte

O app identifica como navegador todo app que abre um link `https` de qualquer site (`BrowserDetector`): o teste usa um domínio inexistente, então apps que só abrem links do próprio site (YouTube, redes sociais) não entram. O bloco `<queries>` do manifesto dá essa visibilidade no Android 11+, sem a permissão de ver todos os apps.

Enquanto houver sites na lista, um navegador que não pertence a nenhuma família é fechado (ação **Início**, com um aviso) assim que mostra uma página web. Exigir conteúdo web na tela evita fechar apps que abrem links sem navegar, como gerenciadores de download. A tela inicial do app lista os navegadores encontrados e se cada um é compatível ou será fechado.

Apps que não são navegadores continuam passando pelo fallback genérico baseado no ID do nó (`url_bar`, `address_bar`, `omnibar`…), que cobre alguns navegadores embutidos em outros apps.

Os IDs vêm dos APKs de cada navegador e podem mudar em atualizações.

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
