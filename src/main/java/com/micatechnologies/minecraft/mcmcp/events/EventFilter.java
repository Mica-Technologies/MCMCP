package com.micatechnologies.minecraft.mcmcp.events;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Which events a waiter wants. Every part is optional, and an empty filter matches everything.
 *
 * <p>Applied in the game rather than by the caller, on purpose: the point of subscribing is that a
 * chat line nobody asked about costs nothing, and filtering after the wire would bring every line
 * across it again.
 */
public final class EventFilter {

    public static final EventFilter ALL = new EventFilter(null, null, null, null);

    private final Set<String> types;
    private final Set<String> players;
    private final Set<String> excludedPlayers;
    @Nullable
    private final String contains;
    /** When set, command events are only matched when this player ran them. */
    @Nullable
    private final String commandsOnlyFrom;

    public EventFilter(@Nullable Collection<String> types, @Nullable Collection<String> players,
        @Nullable Collection<String> excludedPlayers, @Nullable String contains) {
        this(lower(types), lower(players), lower(excludedPlayers),
            contains == null || contains.isEmpty() ? null : contains.toLowerCase(Locale.ROOT), null);
    }

    private EventFilter(Set<String> types, Set<String> players, Set<String> excludedPlayers,
        @Nullable String contains, @Nullable String commandsOnlyFrom) {
        this.types = types;
        this.players = players;
        this.excludedPlayers = excludedPlayers;
        this.contains = contains;
        this.commandsOnlyFrom = commandsOnlyFrom;
    }

    /**
     * This filter, but hiding commands other people ran. For a companion player who may not see
     * other players' business: an operator's commands can name things a player should not learn.
     */
    public EventFilter withCommandsOnlyFrom(String player) {
        return new EventFilter(types, players, excludedPlayers, contains, player.toLowerCase(Locale.ROOT));
    }

    private static Set<String> lower(@Nullable Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                out.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    public boolean matches(GameEvent event) {
        if (!types.isEmpty() && !types.contains(event.getType())) {
            return false;
        }
        String player = event.getPlayer() == null ? null : event.getPlayer().toLowerCase(Locale.ROOT);
        // A player filter asks about someone, so an event nobody is named in does not match it.
        if (!players.isEmpty() && (player == null || !players.contains(player))) {
            return false;
        }
        if (player != null && excludedPlayers.contains(player)) {
            return false;
        }
        if (commandsOnlyFrom != null && GameEvent.COMMAND.equals(event.getType())
            && !commandsOnlyFrom.equals(player)) {
            return false;
        }
        if (contains != null) {
            String text = event.getText();
            return text != null && text.toLowerCase(Locale.ROOT).contains(contains);
        }
        return true;
    }

    /** Types named that no event ever has; reported back so a typo is not a silent forever-wait. */
    public Set<String> unknownTypes() {
        Set<String> unknown = new HashSet<>(types);
        unknown.removeAll(GameEvent.TYPES);
        return unknown;
    }
}
