package br.com.aloi.domain;

public enum LoginType {
    BANNED("banned"), CURRENT("current"), OLD("old"), DOWNLOAD("download");

    private final String key;

    LoginType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
