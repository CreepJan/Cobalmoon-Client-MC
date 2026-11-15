package dev.cobalmoon.core;

import dev.cobalmoon.core.modules.ServerProfilesModule;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ServerProfilesTest {

    @Test
    void exactHostOrParentDomain() {
        Set<String> hosts = Set.of("example.net", "pvp.other.org");
        assertEquals("example.net", ServerProfilesModule.findProfileHost("example.net", hosts));
        assertEquals("example.net", ServerProfilesModule.findProfileHost("mc.eu.example.net", hosts));
        assertEquals("pvp.other.org", ServerProfilesModule.findProfileHost("pvp.other.org", hosts));
        assertNull(ServerProfilesModule.findProfileHost("other.org", hosts), "a parent never uses a subdomain's profile");
        assertNull(ServerProfilesModule.findProfileHost("net", hosts));
        assertNull(ServerProfilesModule.findProfileHost(null, hosts));
    }

    @Test
    void fileNamesAreSafe() {
        assertEquals("play.example.net.json", ServerProfilesModule.fileName("play.example.net"));
        java.nio.file.Path base = java.nio.file.Path.of("profiles");
        java.nio.file.Path resolved = base.resolve(ServerProfilesModule.fileName("../../etc/passwd")).normalize();
        assertEquals(base, resolved.getParent(), "cannot escape the profiles folder: " + resolved);
        assertFalse(ServerProfilesModule.fileName("a/b\\c:d").contains("/"));
        assertFalse(ServerProfilesModule.fileName("a/b\\c:d").contains("\\"));
        assertFalse(ServerProfilesModule.fileName("a/b\\c:d").contains(":"));
    }
}
