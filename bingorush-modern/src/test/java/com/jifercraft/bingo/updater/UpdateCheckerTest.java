package com.jifercraft.bingo.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

    @Test
    void picksTheHighestTagWhateverTheOrder() {
        UpdateChecker checker = new UpdateChecker(null, Version.parse("0.2.0-pre.1"));

        UpdateChecker.CheckResult result = checker.handleTagsJson("""
                [{"name":"v0.1.0-pre.1"},{"name":"v0.2.0-pre.10"},{"name":"v0.2.0-pre.2"},{"name":"v0.1.0-pre.2"}]
                """);

        assertEquals(UpdateChecker.CheckResult.UPDATE_FOUND, result);
        assertEquals("0.2.0-pre.10", checker.latestVersion().clean());
        assertEquals("https://github.com/JainaGam3r45/BingoRush/releases/tag/v0.2.0-pre.10", checker.downloadUrl());
    }

    @Test
    void sameTagIsUpToDate() {
        UpdateChecker checker = new UpdateChecker(null, Version.parse("0.2.0-pre.2"));

        assertEquals(UpdateChecker.CheckResult.UP_TO_DATE,
                checker.handleTagsJson("[{\"name\":\"v0.2.0-pre.1\"},{\"name\":\"v0.2.0-pre.2\"}]"));
    }

    @Test
    void emptyTagListIsNotFound() {
        UpdateChecker checker = new UpdateChecker(null, Version.parse("0.2.0-pre.2"));

        assertEquals(UpdateChecker.CheckResult.NOT_FOUND, checker.handleTagsJson("[]"));
    }

    @Test
    void onlyAPlainGithubLinkBecomesAClickTarget() {
        String tag = "https://github.com/JainaGam3r45/BingoRush/releases/tag/v0.2.0-pre.3";
        assertEquals(tag, UpdateChecker.clickableLink(tag));

        String releases = "https://github.com/JainaGam3r45/BingoRush/releases";
        // A tag name can hold "<" and quotes; those never reach the message as tags.
        assertEquals(releases, UpdateChecker.clickableLink(releases + "/tag/v1<click:run_command:'/op x'>"));
        assertEquals(releases, UpdateChecker.clickableLink("https://example.invalid/x"));
    }

    @Test
    void reusesCachedResultWithinThrottleInterval() throws Exception {
        UpdateCheckPolicy policy = new UpdateCheckPolicy(60_000L);
        UpdateChecker checker = new UpdateChecker(null, Version.parse("0.2.0-pre.2"), policy);
        checker.handleTagsJson("[{\"name\":\"v0.2.0-pre.2\"}]");
        policy.recordCheck(System.currentTimeMillis());

        var future = checker.check();
        org.junit.jupiter.api.Assertions.assertTrue(future.isDone());
        assertEquals(UpdateChecker.CheckResult.UP_TO_DATE, future.get());
    }
}
