# Bloquear Sites

Aplicativo Android simples para bloquear domínios no navegador usando um `AccessibilityService`.

## Como funciona

1. O usuário adiciona um domínio, por exemplo `instagram.com`.
2. O app normaliza e salva apenas o domínio localmente em `SharedPreferences`.
3. Depois que o usuário consente e ativa manualmente o serviço nas Configurações de Acessibilidade do Android, o serviço observa mudanças da interface.
4. Para navegadores conhecidos, o app usa o método da família do navegador (veja abaixo) para achar a barra de endereço.
5. Um navegador fora da lista é testado com o método de cada família; se nenhuma se encaixa, ele é bloqueado enquanto houver sites na lista ou o bloqueio de pornografia estiver ligado. Apps que não são navegadores passam por um fallback genérico que procura nós cujo ID se parece com barra de URL/endereço.
6. A URL visível é normalizada para host e comparada com a lista. `example.com` também bloqueia `www.example.com` e `sub.example.com`, mas não bloqueia `evil-example.com`.
7. Ao detectar um domínio bloqueado, o serviço cobre a tela por alguns instantes e leva o navegador para `google.com` na própria aba: toca na barra de endereço, digita o endereço, confere o texto e confirma com o Enter de acessibilidade (Android 11+); na tela de pesquisa do Mi Browser, com a ação **Ir** do teclado de acessibilidade do serviço (Android 13+). Se a barra não puder ser usada (Android 10 ou anterior, Custom Tabs, navegador não reconhecido ou barra não encontrada), o Google é aberto em uma aba nova (no Firefox, depois da ação **Voltar**).

O app deliberadamente **não declara permissão de Internet**. A lista e as URLs lidas da interface permanecem no aparelho.

## Famílias de navegadores

Navegadores que expõem a barra de endereço do mesmo jeito ficam na mesma família (`BrowserProfiles`), e cada família tem um método de identificação (`BrowserProfile.Method`). Uma diferença na forma de identificação pede uma família própria.

| Família | Pacotes | Método | Como a barra é achada |
|---|---|---|---|
| Chromium | Chrome (estável/Beta/Dev/Canary), Brave, Edge, Vivaldi, Kiwi, Chromium, Cromite, Bromite, Mulch | `VIEW_ID` | `url_bar` (EditText) |
| Firefox | Firefox, Firefox Beta, Nightly, Tor Browser, Fennec F-Droid, Iceraven, Mull | `FIREFOX_TOOLBAR` | barra em Jetpack Compose (`ADDRESSBAR_URL_BOX`, URL lida da descrição de acessibilidade) ou barras antigas em View (`mozac_browser_toolbar_url_view`, `url_bar_title`); só a URL exibida conta, confirmada por leituras estáveis |
| Samsung Internet | Samsung Internet e Beta | `VIEW_ID_WITH_REREAD` | `location_bar_edit_text` (UrlBar) e `compact_url_text` (barra compacta ao rolar); o domínio vem precedido da marca invisível U+200E |
| Mi Browser/AOSP | Mi Browser (`com.mi.globalbrowser`) e navegadores da base AOSP (`com.android.browser`, usado pela MIUI) | `VIEW_ID_WITH_REREAD` | no novo estilo de página (padrão nos celulares desde o Mi Browser 14), o domínio na barra de baixo (`web_bottom_url` e a descrição de `web_bottom_url_click`); na barra antiga (tablets), `url` (UrlInputView), com a URL sem o esquema. Tocar na barra de baixo abre a tela de pesquisa (`et_input`), usada só para digitar o destino |
| Opera | Opera, Opera Beta, Opera Mini | `VIEW_ID` | `url_field` |
| DuckDuckGo | DuckDuckGo | `VIEW_ID` | `omnibarTextInput` |
| Yandex | Yandex e Yandex Beta | `VIEW_ID` | domínio no título central (`bro_omnibar_address_title_text`/`_view`, `bro_omnibox_collapsed_title` recolhida); edição em `suggest_omnibox_query_edit` |
| Barra na tela | Opera GX (barra em Compose, sem IDs) | `TOOLBAR_STRUCTURE` | texto com URL ou domínio junto à borda; em seguida, ID com cara de barra de endereço, sempre fora da página |

- `VIEW_ID_WITH_REREAD` e `TOOLBAR_STRUCTURE` ouvem todos os eventos e releem a barra após um curto atraso quando a primeira leitura falha.
- A troca só começa depois que a cortina aparece na tela (primeiro quadro desenhado, ou até 400 ms). A cortina fica acima do teclado. Eventos do navegador em troca de site são descartados antes de qualquer consulta à árvore.
- Com algo a bloquear, o navegador em uso é lido de novo a cada 2 segundos, mesmo sem eventos, até sair da tela; no Firefox, o mesmo domínio precisa aparecer em duas leituras seguidas. Um site bloqueado que escapou da troca não fica liberado à espera de um evento.
- No Opera GX, a barra não tem IDs e é lida pela estrutura da tela, o que percorre a árvore; os eventos de uma rajada viram uma só leitura, 120 ms depois. A troca pela barra abre uma tela de pesquisa. A cortina fica na tela até a troca terminar (até 12 segundos), e não só os 3 segundos dos outros navegadores. No Android 13+, o destino é digitado pela conexão de entrada do serviço, como um teclado (seleciona tudo e escreve por cima): o `ACTION_SET_TEXT` às vezes não ficava no campo em Compose. A espera pela tela de pesquisa e a conferência do texto usam o teclado do serviço (`ServiceInputMethod`), sem percorrer a árvore. Se a tela de pesquisa for fechada no meio da troca (Voltar), a troca pela barra é tentada até 3 vezes antes da aba nova, e Voltar só é usado se a tela ainda estiver aberta. Se a troca desistir, o site é conferido de novo e bloqueado outra vez se continuar na tela.
- A tela de pesquisa do Mi Browser só navega com a ação **Ir** do teclado; o Enter de acessibilidade chega com outra ação e é ignorado. Por isso o serviço declara `flagInputMethodEditor` e envia a ação pela própria conexão de entrada (Android 13+), sem ler o que é digitado. No Android 12 ou anterior, o Google abre em uma aba nova no Mi Browser.
- Os pacotes das famílias acima formam a lista de navegadores suportados.
- **Navegadores fora da lista** são testados com o método de cada família (`IdentifiedBrowsers`, na ordem Chromium, Firefox, Samsung Internet, Mi Browser/AOSP, Opera, DuckDuckGo e Yandex). A primeira família cujo método lê a URL da barra passa a ser a família do navegador, e o resultado fica salvo. Um derivado do Chrome, por exemplo, se encaixa pelo `url_bar`. A Barra na tela não aceita navegadores desconhecidos: ela depende de a barra mostrar a URL, e navegadores que mostram o título da página deixariam passar sites abertos por links.

### Navegadores bloqueados

Via (`mark.via.gp`, `mark.via`), UC Browser (`com.UCMobile.intl`, `com.UCMobile`) e UC Mini (`com.uc.browser.en`) ficam em `KNOWN_UNSUPPORTED`: nos testes, a barra deles mostrava o título da página, e sites abertos por links ou pela pesquisa ficavam acessíveis. Eles nunca são identificados e são fechados assim que aparecem, enquanto houver sites na lista.

## Navegadores sem suporte

O app identifica como navegador todo app que abre um link `https` de qualquer site (`BrowserDetector`): o teste usa um domínio inexistente, então apps que só abrem links do próprio site (YouTube, redes sociais) não entram. O bloco `<queries>` do manifesto dá essa visibilidade no Android 11+, sem a permissão de ver todos os apps.

Enquanto houver sites na lista ou o bloqueio de pornografia estiver ligado, só os navegadores suportados ficam liberados:

- **Navegador fora da lista:** é testado com o método de cada família, sempre com uma página web na tela. A tela inicial própria de um navegador pode mostrar um endereço que as páginas não mostram. Uma leitura não basta: a mesma família precisa ler a URL da barra, sem falhar, por 2 segundos (`IdentificationConfirmation`). Assim, navegadores que mostram a URL só enquanto a página carrega e depois trocam pelo título ficam de fora.
- **Nenhuma família se encaixa:** o navegador volta para a tela inicial (ação **Início**), com um aviso, e fica registrado como rejeitado. O primeiro bloqueio espera 2 segundos e um novo teste, para não bloquear um navegador que mostra a URL na barra depois do conteúdo. Um navegador já rejeitado é fechado assim que mostra uma página, sem esse prazo, a menos que uma família tenha acabado de ler a URL. Ele continua sendo testado a cada abertura, para o caso de uma atualização passar a funcionar.
- **Rede de segurança (`VerifiedBrowsers`):** mesmo um navegador suportado ou identificado precisa ter a barra achada pelo método da família ao menos uma vez em cada versão, com uma página na tela. A versão é a data da última atualização do navegador e a do próprio app. Se em 5 segundos a barra não aparece, o navegador é fechado; depois de uma falha na mesma versão, o prazo cai para 1,5 segundo. Isso cobre atualizações que mudam a barra, como a do Mi Browser 14. Basta a barra estar na tela, mesmo sem uma URL, porque o Mi Browser mostra os termos pesquisados nas páginas de resultado. Depois de achada, a barra só é conferida de novo na próxima atualização, porque ela some de verdade ao rolar a página (Chrome) e em tela cheia.

Exigir conteúdo web na tela evita fechar apps que abrem links sem navegar, como gerenciadores de download. Apps instalados a partir de sites e abertos pelo Chrome sem barra de endereço (Trusted Web Activities) também são fechados se forem a primeira página depois de uma atualização do Chrome; uma página normal aberta no Chrome confere a barra de novo.

A tela inicial do app lista os navegadores instalados: ✅ suportado (com a família), ⛔ não suportado ou com a barra não lida nesta versão (bloqueado), ❓ em teste (ainda não aberto com uma página).

Apps que não são navegadores continuam passando pelo fallback genérico baseado no ID do nó (`url_bar`, `address_bar`, `omnibar`…), que cobre alguns navegadores embutidos em outros apps.

Os IDs vêm dos APKs de cada navegador e podem mudar em atualizações.

## Bloquear pornografia

A opção **Bloquear pornografia**, na tela inicial, bloqueia conteúdo adulto sem depender da lista de sites (`AdultContentFilter`). Tudo roda no aparelho: o app não tem internet, e as listas vêm dentro dele. Uma página bloqueada é trocada pelo Google, como os sites da lista.

O serviço de acessibilidade só vê texto: ele não analisa o conteúdo das imagens nem dos vídeos. Por isso o filtro usa quatro sinais:

1. **Domínio:** lista de sites de pornografia, webcams e plataformas adultas (com os subdomínios), domínios com trechos como `porn`, `xxx`, `xvideo` e `hentai`, palavras inteiras como `sex` e `sexo` (bloqueia `free-sex.net`, mas não `sussex.ac.uk`) e as terminações `.xxx`, `.porn`, `.sex` e `.adult`.
2. **Endereço:** termos explícitos no caminho ou na busca, como `google.com/search?q=videos+porno` (Google Imagens e Vídeos inclusive) ou `youtube.com/results?search_query=...`. Só funciona nos navegadores que mostram o endereço completo na barra.
3. **Pesquisa:** o texto digitado no campo de busca da página, e o que a barra mostra quando não é uma URL (o Mi Browser mostra os termos pesquisados).
4. **Texto da página (`PageText`):** um pouco depois de a página abrir, o texto dela é conferido. A página é bloqueada se tiver a palavra "porn" (em qualquer palavra: porno, pornô, pornografia) ou a palavra "xxx", se citar dois ou mais sites adultos, como a origem que o Google Imagens mostra embaixo de cada imagem, ou se tiver três ou mais termos explícitos diferentes. O "xxx" de máscaras de formulário, como o CPF `xxx.xxx.xxx-xx`, não conta. Nos buscadores, a conferência se repete a cada 1,5 segundo, porque a pesquisa muda sem mudar o domínio; nas demais páginas, a cada 5 segundos.

Palavras comuns fora da pornografia (sexo, nude, pelada, naked) só contam dentro de expressões ("sexo explícito", "mulheres peladas"), para não bloquear "sexo biológico", "batom nude" ou "pelada de futebol".

Com a opção ligada, os navegadores sem suporte também são fechados, mesmo com a lista de sites vazia.

Limites: uma busca inocente que retorne imagens explícitas sem citar sites adultos nem termos explícitos não é detectada. Sites adultos com nomes comuns, fora da lista, só são pegos pelo texto da página. Para reforçar, ative o SafeSearch na conta Google.

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
