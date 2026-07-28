package gg.nurmi.survivaltweaks.object;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaGuideTopicTest {

    @Test
    void configurationKeysAreStableUniqueAndResolvable() {
        assertEquals(VanillaGuideTopic.values().length, VanillaGuideTopic.keys().size());
        for (VanillaGuideTopic topic : VanillaGuideTopic.values()) {
            assertEquals(topic, VanillaGuideTopic.fromKey(topic.key()).orElseThrow());
        }
        assertTrue(VanillaGuideTopic.fromKey("unknown").isEmpty());
    }
}
