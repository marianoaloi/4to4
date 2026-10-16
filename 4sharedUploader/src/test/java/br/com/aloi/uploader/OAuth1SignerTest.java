package br.com.aloi.uploader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OAuth1SignerTest {
    @Test
    void encodesPerOauthPercentEncodingRules() {
        assertEquals("Ladies%20%2B%20Gentlemen", OAuth1Signer.encode("Ladies + Gentlemen"));
        assertEquals("%E2%98%83", OAuth1Signer.encode("☃"));
        assertEquals("abc~._-09", OAuth1Signer.encode("abc~._-09"));
    }
}
