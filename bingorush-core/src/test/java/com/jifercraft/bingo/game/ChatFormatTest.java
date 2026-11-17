package com.jifercraft.bingo.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ChatFormatTest {

    @Test
    void placeholdersBecomeTheBukkitArguments() {
        String format = ChatFormat.toFormat("[Red] " + ChatFormat.PLAYER_TOKEN + ": " + ChatFormat.MESSAGE_TOKEN);

        assertEquals("[Red] Steve: hello", String.format(format, "Steve", "hello"));
    }

    @Test
    void aPercentSignInATeamNameCannotBreakChat() {
        String format = ChatFormat.toFormat("[100%] " + ChatFormat.PLAYER_TOKEN + " " + ChatFormat.MESSAGE_TOKEN);

        assertEquals("[100%] Steve 50% done", String.format(format, "Steve", "50% done"));
    }

    @Test
    void aTemplateWithoutTheMessageIsRejected() {
        assertNull(ChatFormat.toFormat("[Red] " + ChatFormat.PLAYER_TOKEN));
        assertNull(ChatFormat.toFormat(null));
    }

    @Test
    void initialHandlesOddNames() {
        assertEquals("R", TeamLabel.initial("rojo"));
        assertEquals("A", TeamLabel.initial("  azul"));
        assertEquals("?", TeamLabel.initial("   "));
        assertEquals("?", TeamLabel.initial(null));
        // A supplementary character is one letter, not half of a surrogate pair.
        assertEquals("\uD835\uDC00", TeamLabel.initial("\uD835\uDC00lpha"));
    }
}
