package com.mycroft.bloquearsites;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filtro de pornografia (opção "Bloquear pornografia"). Tudo roda no aparelho: o app não tem
 * internet, então as listas vêm dentro dele.
 *
 * O serviço de acessibilidade só vê texto, nunca o conteúdo das imagens e dos vídeos. Por isso o
 * filtro olha:
 * - o domínio: lista de sites adultos, trechos como "porn" e "xxx" e as terminações .xxx, .porn,
 *   .sex e .adult;
 * - o resto do endereço e a pesquisa feita: termos explícitos no caminho ou na busca;
 * - o texto da página: sites adultos citados nos resultados (o Google Imagens mostra a origem de
 *   cada imagem) ou vários termos explícitos diferentes na mesma página.
 *
 * Palavras comuns fora da pornografia (sexo, nude, pelada, naked) só contam dentro de expressões,
 * para não bloquear "sexo biológico", "batom nude" ou "pelada de futebol".
 */
final class AdultContentFilter {
    // Sites adultos citados nos resultados de uma busca a partir dos quais a página é bloqueada.
    static final int ADULT_SITES_IN_PAGE = 2;
    // Termos explícitos diferentes numa página a partir dos quais ela é bloqueada.
    static final int EXPLICIT_TERMS_IN_PAGE = 3;

    // Sites de pornografia, webcams e plataformas de conteúdo adulto; vale para os subdomínios.
    // Sites fora da lista são pegos pelos trechos do domínio ou pelo texto da página.
    private static final Set<String> ADULT_DOMAINS = set(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
            "youporn.com", "tube8.com", "spankbang.com", "eporner.com", "youjizz.com",
            "beeg.com", "tnaflix.com", "empflix.com", "drtuber.com", "txxx.com", "hclips.com",
            "hdzog.com", "upornia.com", "thumbzilla.com", "keezmovies.com", "extremetube.com",
            "mofosex.com", "motherless.com", "heavy-r.com", "xtube.com", "efukt.com",
            "noodlemagazine.com", "4tube.com", "fuq.com", "ixxx.com", "daftsex.com",
            "sxyprn.com", "porntrex.com", "hqporner.com", "vjav.com", "javhd.com",
            "javlibrary.com", "jable.tv", "missav.com", "supjav.com", "rule34video.com",
            "chaturbate.com", "stripchat.com", "bongacams.com", "livejasmin.com", "cam4.com",
            "camsoda.com", "myfreecams.com", "flirt4free.com", "imlive.com", "streamate.com",
            "onlyfans.com", "fansly.com", "manyvids.com", "clips4sale.com", "brazzers.com",
            "realitykings.com", "bangbros.com", "naughtyamerica.com", "mofos.com",
            "digitalplayground.com", "vixen.com", "blacked.com", "tushy.com", "evilangel.com",
            "kink.com", "playboy.com", "penthouse.com", "hustler.com", "adultfriendfinder.com",
            "fetlife.com", "literotica.com", "nhentai.net", "e-hentai.org", "exhentai.org",
            "hanime.tv", "rule34.xxx", "rule34.paheal.net", "gelbooru.com", "e621.net",
            "sankakucomplex.com", "erome.com", "fapello.com", "coomer.su", "coomer.party",
            "kemono.su", "kemono.party", "redgifs.com", "scrolller.com", "imagefap.com",
            "porn.com", "sex.com", "xxx.com", "brasileirinhas.com.br", "sexyhot.com.br",
            "sexlog.com", "casadoscontos.com.br", "privacy.com.br"
    );

    private static final Set<String> ADULT_TLDS = set("xxx", "porn", "sex", "adult");

    // Trechos que indicam pornografia em qualquer parte do domínio.
    private static final String[] ADULT_HOST_FRAGMENTS = {
            "porn", "xvideo", "xnxx", "xhamster", "hentai", "xxx", "redtube", "youjizz",
            "brazzers", "chaturbate", "stripchat", "bongacams", "livejasmin", "spankbang",
            "onlyfans", "nsfw", "camsex", "sexcam"
    };

    // Partes inteiras do domínio (entre pontos ou hifens): "sex" bloqueia free-sex.com, mas não
    // sussex.ac.uk.
    private static final Set<String> ADULT_HOST_WORDS = set(
            "sex", "sexo", "porno", "milf", "putaria", "erotic", "erotica", "erotico",
            "eroticos", "eroticas", "boobs", "tits"
    );

    // Palavras que sozinhas bastam numa busca ou num endereço.
    private static final Set<String> EXPLICIT_WORDS = set(
            // Inglês
            "xxx", "nsfw", "milf", "milfs", "ecchi", "yiff", "rule34", "futanari", "bdsm",
            "blowjob", "blowjobs", "cumshot", "cumshots", "creampie", "gangbang", "bukkake",
            "deepthroat", "handjob", "footjob", "horny", "pussy", "tits", "boobs", "camgirl",
            "camgirls", "striptease", "erotic",
            // Português
            "putaria", "putarias", "buceta", "bucetas", "bucetinha", "xoxota", "xereca",
            "boquete", "boquetes", "punheta", "siririca", "piroca", "cuzinho", "transando",
            "fodendo", "erotico", "erotica", "eroticos", "eroticas",
            // Espanhol
            "desnuda", "desnudas", "desnudo", "desnudos", "follando",
            // Sites e plataformas
            "onlyfans", "redtube", "youporn", "brazzers", "chaturbate", "stripchat", "spankbang",
            "bongacams", "livejasmin", "fansly"
    );

    // Radicais: contam em qualquer palavra que os contenha (porn, pornô, pornografia, pornhub...).
    private static final String[] EXPLICIT_STEMS = {"porn", "hentai", "xvideo", "xnxx", "xhamster"};

    // Expressões: as palavras isoladas são comuns fora da pornografia.
    private static final List<String[]> EXPLICIT_PHRASES = phrases(
            "sexo explicito", "video de sexo", "videos de sexo", "filme de sexo",
            "filmes de sexo", "sexo anal", "sexo oral", "sexo gratis", "sexo amador",
            "fazendo sexo", "mulher pelada", "mulheres peladas", "fotos peladas",
            "foto pelada", "nudes vazados", "nudes vazadas", "fotos nudes", "pau duro",
            "garota de programa", "garotas de programa", "sex video", "sex videos", "sex tape",
            "sex cam", "sex cams", "free sex", "anal sex", "oral sex", "nude pics",
            "nude photos", "nude videos", "naked women", "strip tease"
    );

    // Buscadores: a pesquisa muda sem mudar o domínio, e a página é conferida de novo mais cedo.
    private static final Set<String> SEARCH_HOSTS = set(
            "bing.com", "duckduckgo.com", "search.yahoo.com", "ecosia.org", "search.brave.com",
            "startpage.com", "qwant.com", "youtube.com"
    );

    private static final Pattern DOMAIN_IN_TEXT =
            Pattern.compile("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}");

    private AdultContentFilter() {}

    /** Domínio de site adulto (ou subdomínio de um). */
    static boolean isAdultHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("www.")) value = value.substring(4);

        for (String domain : ADULT_DOMAINS) {
            if (value.equals(domain) || value.endsWith("." + domain)) return true;
        }

        int lastDot = value.lastIndexOf('.');
        if (lastDot >= 0 && ADULT_TLDS.contains(value.substring(lastDot + 1))) return true;

        for (String fragment : ADULT_HOST_FRAGMENTS) {
            if (value.contains(fragment)) return true;
        }

        for (String word : value.split("[.-]")) {
            if (ADULT_HOST_WORDS.contains(word)) return true;
        }
        return false;
    }

    /** Endereço de site adulto, ou com termos explícitos no caminho ou na busca. */
    static boolean blocksUrl(String url) {
        String host = DomainMatcher.extractHost(url);
        if (host == null) return false;
        if (isAdultHost(host)) return true;

        String rest = afterHost(url, host);
        return !rest.isEmpty() && isExplicitText(decode(rest));
    }

    /** Texto (busca, endereço, título) com algum termo explícito. */
    static boolean isExplicitText(String text) {
        return !explicitTerms(text).isEmpty();
    }

    /**
     * Página de conteúdo adulto pelo texto dela.
     *
     * @param texts  textos da página
     * @param fields textos digitados em campos (a busca feita)
     */
    static boolean isAdultPage(Collection<String> texts, Collection<String> fields) {
        for (String field : fields) {
            if (isExplicitText(field)) return true;
        }

        Set<String> adultSites = new HashSet<>();
        Set<String> terms = new HashSet<>();
        for (String text : texts) {
            if (text == null || text.isEmpty()) continue;

            Matcher matcher = DOMAIN_IN_TEXT.matcher(text.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                String domain = matcher.group();
                if (isAdultHost(domain)) adultSites.add(stripWww(domain));
            }
            terms.addAll(explicitTerms(text));

            if (adultSites.size() >= ADULT_SITES_IN_PAGE) return true;
            if (terms.size() >= EXPLICIT_TERMS_IN_PAGE) return true;
        }
        return false;
    }

    /** Buscador, onde a pesquisa muda sem mudar o domínio. */
    static boolean isSearchHost(String host) {
        if (host == null) return false;
        String value = stripWww(host.toLowerCase(Locale.ROOT));
        if (value.startsWith("google.") || value.contains(".google.")) return true;
        if (value.startsWith("yandex.") || value.contains(".yandex.")) return true;
        for (String search : SEARCH_HOSTS) {
            if (value.equals(search) || value.endsWith("." + search)) return true;
        }
        return false;
    }

    /** Termos explícitos distintos no texto: cada radical, palavra ou expressão conta uma vez. */
    static Set<String> explicitTerms(String text) {
        if (text == null || text.isEmpty()) return Collections.emptySet();

        String[] words = normalize(text).split(" ");
        Set<String> found = new HashSet<>();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (EXPLICIT_WORDS.contains(word)) found.add(word);
            for (String stem : EXPLICIT_STEMS) {
                if (word.contains(stem)) found.add(stem);
            }
        }

        for (String[] phrase : EXPLICIT_PHRASES) {
            if (containsPhrase(words, phrase)) found.add(String.join(" ", phrase));
        }
        return found;
    }

    /** Minúsculas, sem acentos, só letras e números separados por um espaço. */
    static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        return decomposed.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static boolean containsPhrase(String[] words, String[] phrase) {
        for (int start = 0; start + phrase.length <= words.length; start++) {
            boolean matches = true;
            for (int i = 0; i < phrase.length; i++) {
                if (!phrase[i].equals(words[start + i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return true;
        }
        return false;
    }

    /** Caminho, busca e fragmento do endereço (o que vem depois do domínio). */
    private static String afterHost(String url, String host) {
        String lower = url.toLowerCase(Locale.ROOT);
        int index = lower.indexOf(host);
        if (index < 0 && host.contains(".")) {
            index = lower.indexOf("www." + host);
            if (index >= 0) index += 4;
        }
        if (index < 0) return "";
        return url.substring(index + host.length());
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String stripWww(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static List<String[]> phrases(String... values) {
        List<String[]> result = new ArrayList<>();
        for (String value : values) result.add(value.split(" "));
        return Collections.unmodifiableList(result);
    }
}
