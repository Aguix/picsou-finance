package com.picsou.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoursoramaLabelsTest {

    @Test
    void mapsKnownSectorsToKeys() {
        assertThat(BoursoramaLabels.sectorKey("Technologie")).isEqualTo("technology");
        assertThat(BoursoramaLabels.sectorKey("Services de communication")).isEqualTo("communication_services");
        assertThat(BoursoramaLabels.sectorKey("Biens de consommation défensif")).isEqualTo("consumer_defensive");
        assertThat(BoursoramaLabels.sectorKey("Matières premières de base")).isEqualTo("basic_materials");
    }

    @Test
    void mapsKnownCountriesToIsoKeys() {
        assertThat(BoursoramaLabels.countryKey("Etats-Unis")).isEqualTo("US");
        assertThat(BoursoramaLabels.countryKey("États-Unis")).isEqualTo("US");
        assertThat(BoursoramaLabels.countryKey("Pays-Bas")).isEqualTo("NL");
        assertThat(BoursoramaLabels.countryKey("Royaume-Uni")).isEqualTo("GB");
    }

    @Test
    void passesUnknownLabelsThroughVerbatim() {
        assertThat(BoursoramaLabels.sectorKey("Cryptomonnaies")).isEqualTo("Cryptomonnaies");
        assertThat(BoursoramaLabels.countryKey("Atlantide")).isEqualTo("Atlantide");
    }

    @Test
    void isWhitespaceAndCaseTolerant() {
        assertThat(BoursoramaLabels.sectorKey("  technologie  ")).isEqualTo("technology");
        assertThat(BoursoramaLabels.countryKey(" etats-unis ")).isEqualTo("US");
    }
}
