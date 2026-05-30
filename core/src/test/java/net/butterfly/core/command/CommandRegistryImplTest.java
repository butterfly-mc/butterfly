package net.butterfly.core.command;

import net.butterfly.api.command.Command;
import net.butterfly.api.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistryImplTest {

    /** Minimal Command stub used purely for registry semantics. */
    private static Command cmd(String name, String... aliases) {
        return new Command() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<String> aliases() {
                return Arrays.asList(aliases);
            }

            @Override
            public void execute(CommandSender sender, String[] args) {
                // no-op
            }

            @Override
            public String toString() {
                return "cmd(" + name + ")";
            }
        };
    }

    @Test
    void registerAndGetByNameIsCaseInsensitive() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        Command stop = cmd("stop");
        reg.register(stop);

        assertSame(stop, reg.get("stop"));
        assertSame(stop, reg.get("STOP"));
        assertSame(stop, reg.get("Stop"));
    }

    @Test
    void registerAndGetByAliasIsCaseInsensitive() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        Command help = cmd("help", "?", "h");
        reg.register(help);

        assertSame(help, reg.get("?"));
        assertSame(help, reg.get("h"));
        assertSame(help, reg.get("H"));
    }

    @Test
    void getReturnsNullForUnknownName() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        assertNull(reg.get("ghost"));
        assertNull(reg.get(null));
    }

    @Test
    void registerCollidingPrimaryNameThrows() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        reg.register(cmd("stop"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> reg.register(cmd("STOP")));
        assertEquals("stop", ex.getMessage());
    }

    @Test
    void registerCollidingAliasThrows() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        reg.register(cmd("help", "?"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> reg.register(cmd("question", "?")));
        assertEquals("?", ex.getMessage());
    }

    @Test
    void registerWhereNewNameMatchesExistingAliasThrows() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        reg.register(cmd("help", "info"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> reg.register(cmd("info")));
        assertEquals("info", ex.getMessage());
    }

    @Test
    void registerWhereNewAliasMatchesExistingPrimaryNameThrows() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        reg.register(cmd("stop"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> reg.register(cmd("halt", "stop")));
        assertEquals("stop", ex.getMessage());
    }

    @Test
    void registerCollisionDoesNotPartiallyApply() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        reg.register(cmd("help", "?"));

        // 'fresh' is unique, but its alias '?' collides; the whole registration must roll back.
        assertThrows(IllegalArgumentException.class,
                () -> reg.register(cmd("fresh", "?")));

        assertNull(reg.get("fresh"));
        assertEquals(1, reg.all().size());
    }

    @Test
    void unregisterRemovesNameAndAliases() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        Command help = cmd("help", "?", "h");
        reg.register(help);

        assertTrue(reg.unregister("help"));
        assertNull(reg.get("help"));
        assertNull(reg.get("?"));
        assertNull(reg.get("h"));

        // The freed name is now reusable.
        Command fresh = cmd("help");
        reg.register(fresh);
        assertSame(fresh, reg.get("help"));
    }

    @Test
    void unregisterIsCaseInsensitive() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        reg.register(cmd("Stop"));
        assertTrue(reg.unregister("STOP"));
        assertNull(reg.get("stop"));
    }

    @Test
    void unregisterReturnsFalseForUnknownName() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        reg.register(cmd("stop"));

        assertFalse(reg.unregister("ghost"));
        assertFalse(reg.unregister(null));
    }

    @Test
    void unregisterByAliasIsNotSupported() {
        // Spec says: unregister by primary name. Calling with an alias should not remove anything.
        CommandRegistryImpl reg = new CommandRegistryImpl();
        reg.register(cmd("help", "?"));

        assertFalse(reg.unregister("?"));
        assertNotNull(reg.get("help"));
        assertNotNull(reg.get("?"));
    }

    @Test
    void allReturnsDistinctCommandsInInsertionOrder() {
        CommandRegistryImpl reg = new CommandRegistryImpl();
        Command a = cmd("alpha", "a");
        Command b = cmd("bravo", "b");
        Command c = cmd("charlie");
        reg.register(a);
        reg.register(b);
        reg.register(c);

        Collection<Command> all = reg.all();
        assertEquals(3, all.size());

        Iterator<Command> it = all.iterator();
        assertSame(a, it.next());
        assertSame(b, it.next());
        assertSame(c, it.next());
    }
}
