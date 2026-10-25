package br.com.aloi.domain;

/** OAuth credentials for one account. Secret values are intentionally redacted from toString(). */
public record AccountCredentials(
        String login,
        String consumerKey,
        String consumerSecret,
        String accessToken,
        String accessTokenSecret,
        String rootFolderId) {

    @Override
    public String toString() {
        return "AccountCredentials[login=" + login + ", consumerKey=[redacted], consumerSecret=[redacted], "
                + "accessToken=[redacted], accessTokenSecret=[redacted], rootFolderId=[redacted]]";
    }
}
