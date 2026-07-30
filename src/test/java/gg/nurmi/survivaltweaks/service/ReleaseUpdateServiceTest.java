package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseUpdateServiceTest {

    @Test
    void semanticVersionsAreComparedNumerically() {
        assertTrue(ReleaseUpdateService.isNewer("v2.18.0", "2.17.1"));
        assertTrue(ReleaseUpdateService.isNewer("2.10.0", "2.9.9"));
        assertTrue(ReleaseUpdateService.isNewer("2.18.0", "2.18.0-rc.1"));
        assertFalse(ReleaseUpdateService.isNewer("2.18.0", "2.18.0"));
        assertFalse(ReleaseUpdateService.isNewer("2.17.1", "2.18.0"));
    }

    @Test
    void githubReleaseResponseIsParsed() {
        ReleaseUpdateService.Release release = ReleaseUpdateService.parseRelease("""
                {
                  "tag_name":"v2.18.0",
                  "assets":[{
                    "name":"SurvivalTweaks-2.18.0.jar",
                    "uploader":{"login":"release-bot"},
                    "browser_download_url":
                      "https://github.com/example/SurvivalTweaks/releases/download/v2.18.0/SurvivalTweaks-2.18.0.jar"
                  }]
                }
                """, "SurvivalTweaks");

        assertEquals("2.18.0", release.version());
        assertEquals(
                "https://github.com/example/SurvivalTweaks/releases/download/v2.18.0/SurvivalTweaks-2.18.0.jar",
                release.downloadUrl()
        );
    }

    @Test
    void repositoryApiIsDerivedFromPluginMetadata() {
        assertEquals(
                "https://api.github.com/repos/example/CustomTweaks/releases/latest",
                ReleaseUpdateService.repositoryApi(
                        "https://github.com/example/CustomTweaks"
                ).toString()
        );
    }
}
