package net.butterfly.core.network.packets.start_game;

/**
 * StartGame's {@code EducationSharedResourceURI} sub-struct — two strings
 * written inline (no enclosing length).
 */
public record EducationSharedResourceURI(String buttonName, String linkUri) {
    public static final EducationSharedResourceURI EMPTY = new EducationSharedResourceURI("", "");
}
