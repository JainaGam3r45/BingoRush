package com.jifercraft.bingo.arena;

/**
 * A value an arena setting does not take. The platform turns it into the
 * arena-option.&lt;key&gt; message, which lists what the setting accepts.
 */
public final class InvalidArenaValue extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final ArenaOption option;
    private final String value;

    public InvalidArenaValue(ArenaOption option, String value) {
        super(option.key() + " does not take '" + value + "'");
        this.option = option;
        this.value = value;
    }

    public ArenaOption option() {
        return option;
    }

    public String value() {
        return value;
    }
}
