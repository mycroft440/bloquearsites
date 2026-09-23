package com.mycroft.bloquearsites;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdultContentFilterTest {
    @Test
    public void knownAdultSitesAndSubdomainsAreBlocked() {
        assertTrue(AdultContentFilter.isAdultHost("pornhub.com"));
        assertTrue(AdultContentFilter.isAdultHost("pt.pornhub.com"));
        assertTrue(AdultContentFilter.isAdultHost("www.xvideos.com"));
        assertTrue(AdultContentFilter.isAdultHost("onlyfans.com"));
        assertTrue(AdultContentFilter.isAdultHost("privacy.com.br"));
    }

    @Test
    public void adultHostNamesAndTldsAreBlocked() {
        assertTrue(AdultContentFilter.isAdultHost("videos-porno-gratis.net"));
        assertTrue(AdultContentFilter.isAdultHost("example.xxx"));
        assertTrue(AdultContentFilter.isAdultHost("site.porn"));
        assertTrue(AdultContentFilter.isAdultHost("free-sex.net"));
        assertTrue(AdultContentFilter.isAdultHost("hentaihaven.org"));
    }

    @Test
    public void ordinaryHostsAreNotBlocked() {
        assertFalse(AdultContentFilter.isAdultHost("google.com"));
        assertFalse(AdultContentFilter.isAdultHost("sussex.ac.uk"));
        assertFalse(AdultContentFilter.isAdultHost("essex.gov.uk"));
        assertFalse(AdultContentFilter.isAdultHost("sextafeira.com.br"));
        assertFalse(AdultContentFilter.isAdultHost("nude-project.com"));
        assertFalse(AdultContentFilter.isAdultHost("youtube.com"));
    }

    @Test
    public void explicitSearchesAreBlocked() {
        assertTrue(AdultContentFilter.isExplicitText("vídeos pornô grátis"));
        assertTrue(AdultContentFilter.isExplicitText("Mulheres PELADAS"));
        assertTrue(AdultContentFilter.isExplicitText("sexo explícito"));
        assertTrue(AdultContentFilter.isExplicitText("hentai"));
        assertTrue(AdultContentFilter.isExplicitText("free sex videos"));
        assertTrue(AdultContentFilter.isExplicitText("xvideos brasil"));
    }

    @Test
    public void commonWordsOutsidePornographyAreNotBlocked() {
        assertFalse(AdultContentFilter.isExplicitText("sexo biológico"));
        assertFalse(AdultContentFilter.isExplicitText("educação sexual nas escolas"));
        assertFalse(AdultContentFilter.isExplicitText("batom nude"));
        assertFalse(AdultContentFilter.isExplicitText("pelada de futebol no sábado"));
        assertFalse(AdultContentFilter.isExplicitText("receita de bolo"));
        assertFalse(AdultContentFilter.isExplicitText(null));
    }

    @Test
    public void explicitGoogleSearchUrlsAreBlocked() {
        assertTrue(AdultContentFilter.blocksUrl("google.com/search?q=videos+porno&tbm=vid"));
        assertTrue(AdultContentFilter.blocksUrl(
                "https://www.google.com.br/search?q=mulheres%20peladas&udm=2"));
        assertTrue(AdultContentFilter.blocksUrl("https://m.youtube.com/results?search_query=hentai"));
        assertTrue(AdultContentFilter.blocksUrl("pornhub.com"));
    }

    @Test
    public void ordinaryUrlsAreNotBlocked() {
        assertFalse(AdultContentFilter.blocksUrl("google.com"));
        assertFalse(AdultContentFilter.blocksUrl("google.com/search?q=receita+de+bolo&udm=2"));
        assertFalse(AdultContentFilter.blocksUrl("g1.globo.com/saude/sexo-biologico.html"));
        assertFalse(AdultContentFilter.blocksUrl("receita de bolo"));
    }

    @Test
    public void searchResultsCitingAdultSitesAreBlocked() {
        List<String> results = Arrays.asList(
                "Imagem 1", "pt.pornhub.com", "Resultado", "www.xvideos.com");

        assertTrue(AdultContentFilter.isAdultPage(results, Collections.emptyList()));
    }

    @Test
    public void oneAdultSiteInTheResultsIsNotEnough() {
        // Um site sem "porn" nem "xxx" no nome: esses dois bloqueiam sozinhos.
        List<String> results = Arrays.asList("Notícia sobre criadores", "onlyfans.com", "g1.globo.com");

        assertFalse(AdultContentFilter.isAdultPage(results, Collections.emptyList()));
    }

    @Test
    public void explicitSearchFieldBlocksThePage() {
        assertTrue(AdultContentFilter.isAdultPage(
                Collections.singletonList("Todas Imagens Vídeos"),
                Collections.singletonList("mulheres peladas")));
    }

    @Test
    public void pagesWithSeveralExplicitTermsAreBlocked() {
        List<String> page = Arrays.asList("Free videos", "MILF", "Hentai collection", "XXX");

        assertTrue(AdultContentFilter.isAdultPage(page, Collections.emptyList()));
    }

    @Test
    public void pornOrXxxAloneBlocksThePage() {
        assertTrue(AdultContentFilter.isAdultPage(
                Arrays.asList("Notícias", "Os melhores vídeos pornô"), Collections.emptyList()));
        assertTrue(AdultContentFilter.isAdultPage(
                Arrays.asList("Galeria", "Fotos XXX 2024"), Collections.emptyList()));
        assertTrue(AdultContentFilter.isAdultPage(
                Collections.singletonList("Pornography"), Collections.emptyList()));
        assertTrue(AdultContentFilter.isExplicitText("xxx"));
        assertTrue(AdultContentFilter.isExplicitText("porn"));
    }

    @Test
    public void formMasksWithXxxAreNotBlocked() {
        List<String> form = Arrays.asList(
                "CPF: xxx.xxx.xxx-xx",
                "xxx.123.456-xx",
                "Telefone (xx) xxxxx-xxxx",
                "Cartão xxxx xxxx xxxx 1234");

        assertFalse(AdultContentFilter.isAdultPage(form, Collections.singletonList("xxx.xxx.xxx-xx")));
    }

    @Test
    public void ordinaryPagesAreNotBlocked() {
        List<String> page = Arrays.asList(
                "Receitas", "Bolo de cenoura", "Sexo biológico e identidade", "Batom nude");

        assertFalse(AdultContentFilter.isAdultPage(page, Collections.singletonList("bolo")));
    }

    @Test
    public void searchEnginesAreRecognized() {
        assertTrue(AdultContentFilter.isSearchHost("google.com"));
        assertTrue(AdultContentFilter.isSearchHost("www.google.com.br"));
        assertTrue(AdultContentFilter.isSearchHost("bing.com"));
        assertTrue(AdultContentFilter.isSearchHost("m.youtube.com"));
        assertFalse(AdultContentFilter.isSearchHost("g1.globo.com"));
        assertFalse(AdultContentFilter.isSearchHost(null));
    }
}
