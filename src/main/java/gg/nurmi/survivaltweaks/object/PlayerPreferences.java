package gg.nurmi.survivaltweaks.object;

import java.util.Objects;

public record PlayerPreferences(
        boolean soundsEnabled,
        boolean particlesEnabled,
        boolean dialogsEnabled,
        boolean actionBarEnabled,
        boolean reducedEffects,
        boolean playerListEnabled,
        boolean mentionNotificationsEnabled,
        boolean journeyGuidanceEnabled,
        boolean publicProfileEnabled,
        boolean mailEnabled,
        LanguagePreference language
) {

    public static final PlayerPreferences DEFAULTS = new PlayerPreferences(
            true,
            true,
            true,
            true,
            false,
            true,
            true,
            true,
            true,
            true,
            LanguagePreference.AUTO
    );

    public PlayerPreferences {
        Objects.requireNonNull(language, "language");
    }

    public PlayerPreferences withSounds(boolean enabled) {
        return new PlayerPreferences(
                enabled, particlesEnabled, dialogsEnabled, actionBarEnabled,
                reducedEffects, playerListEnabled, mentionNotificationsEnabled,
                journeyGuidanceEnabled, publicProfileEnabled, mailEnabled, language
        );
    }

    public PlayerPreferences withParticles(boolean enabled) {
        return new PlayerPreferences(
                soundsEnabled, enabled, dialogsEnabled, actionBarEnabled,
                reducedEffects, playerListEnabled, mentionNotificationsEnabled,
                journeyGuidanceEnabled, publicProfileEnabled, mailEnabled, language
        );
    }

    public PlayerPreferences withDialogs(boolean enabled) {
        return new PlayerPreferences(
                soundsEnabled, particlesEnabled, enabled, actionBarEnabled,
                reducedEffects, playerListEnabled, mentionNotificationsEnabled,
                journeyGuidanceEnabled, publicProfileEnabled, mailEnabled, language
        );
    }

    public PlayerPreferences withActionBar(boolean enabled) {
        return new PlayerPreferences(
                soundsEnabled, particlesEnabled, dialogsEnabled, enabled,
                reducedEffects, playerListEnabled, mentionNotificationsEnabled,
                journeyGuidanceEnabled, publicProfileEnabled, mailEnabled, language
        );
    }

    public PlayerPreferences withReducedEffects(boolean enabled) {
        return new PlayerPreferences(
                soundsEnabled, particlesEnabled, dialogsEnabled, actionBarEnabled,
                enabled, playerListEnabled, mentionNotificationsEnabled,
                journeyGuidanceEnabled, publicProfileEnabled, mailEnabled, language
        );
    }

    public PlayerPreferences withPlayerList(boolean enabled) {
        return new PlayerPreferences(
                soundsEnabled, particlesEnabled, dialogsEnabled, actionBarEnabled,
                reducedEffects, enabled, mentionNotificationsEnabled,
                journeyGuidanceEnabled, publicProfileEnabled, mailEnabled, language
        );
    }

    public PlayerPreferences withMentionNotifications(boolean enabled) {
        return new PlayerPreferences(
                soundsEnabled, particlesEnabled, dialogsEnabled, actionBarEnabled,
                reducedEffects, playerListEnabled, enabled,
                journeyGuidanceEnabled, publicProfileEnabled, mailEnabled, language
        );
    }

    public PlayerPreferences withJourneyGuidance(boolean enabled) {
        return new PlayerPreferences(
                soundsEnabled, particlesEnabled, dialogsEnabled, actionBarEnabled,
                reducedEffects, playerListEnabled, mentionNotificationsEnabled,
                enabled, publicProfileEnabled, mailEnabled, language
        );
    }

    public PlayerPreferences withPublicProfile(boolean enabled) {
        return new PlayerPreferences(
                soundsEnabled, particlesEnabled, dialogsEnabled, actionBarEnabled,
                reducedEffects, playerListEnabled, mentionNotificationsEnabled,
                journeyGuidanceEnabled, enabled, mailEnabled, language
        );
    }

    public PlayerPreferences withMail(boolean enabled) {
        return new PlayerPreferences(
                soundsEnabled, particlesEnabled, dialogsEnabled, actionBarEnabled,
                reducedEffects, playerListEnabled, mentionNotificationsEnabled,
                journeyGuidanceEnabled, publicProfileEnabled, enabled, language
        );
    }

    public PlayerPreferences withLanguage(LanguagePreference updatedLanguage) {
        return new PlayerPreferences(
                soundsEnabled, particlesEnabled, dialogsEnabled, actionBarEnabled,
                reducedEffects, playerListEnabled, mentionNotificationsEnabled,
                journeyGuidanceEnabled, publicProfileEnabled, mailEnabled, updatedLanguage
        );
    }
}
