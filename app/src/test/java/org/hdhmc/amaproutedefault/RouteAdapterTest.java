package org.hdhmc.amaproutedefault;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RouteAdapterTest {
    public enum RenamedMode {
        DEFAULT(-1), CAR(0), BUS(1), ONFOOT(2), RIDE(3), TAXI(6);
        private final int number;
        RenamedMode(int value) { number = value; }
        public int obfuscatedAccessor() { return number; }
    }
    public interface Poi { String getName(); Object getPoint(); }
    public static class RenamedRequest {
        private RenamedMode renamedSelection;
        public Poi origin;
        public Poi destination;
        public boolean fromLink;
        public String source;
    }
    public static class RenamedResult {
        final RenamedMode renamedType;
        public RenamedResult(RenamedMode mode) { renamedType = mode; }
    }
    public interface RenamedCallback { void renamedResult(RenamedResult result); }
    public static void renamedInitializer(RenamedRequest request, RenamedCallback callback) {}

    private RouteAdapter adapter() throws Exception {
        return RouteAdapter.validate(getClass().getMethod("renamedInitializer", RenamedRequest.class, RenamedCallback.class));
    }

    @Test public void resolvesRenamedClassesMethodsFieldsAndEnumAccessor() throws Exception {
        RouteAdapter adapter = adapter();
        assertEquals("renamedSelection", adapter.routeField.getName());
        RenamedRequest input = new RenamedRequest();
        Object expectedDestination = new Poi() {
            public String getName() { return "destination"; }
            public Object getPoint() { return this; }
        };
        input.destination = (Poi) expectedDestination;
        input.source = "caller";
        input.fromLink = true;
        RenamedRequest output = (RenamedRequest) adapter.replacement(input, "BUS");
        assertNotSame(input, output);
        assertNull(input.renamedSelection);
        assertEquals(RenamedMode.BUS, output.renamedSelection);
        assertSame(expectedDestination, output.destination);
        assertEquals("caller", output.source);
        assertTrue(output.fromLink);
    }

    @Test public void preservesEveryExplicitModeIncludingTaxi() throws Exception {
        RouteAdapter adapter = adapter();
        for (RenamedMode explicit : RenamedMode.values()) {
            if (explicit == RenamedMode.DEFAULT) continue;
            RenamedRequest input = new RenamedRequest();
            input.renamedSelection = explicit;
            assertNull(adapter.replacement(input, "BUS"));
            assertEquals(explicit, input.renamedSelection);
        }
    }

    @Test public void supportsNullAndAutomaticRequestsAndAllChoices() throws Exception {
        RouteAdapter adapter = adapter();
        for (String mode : new String[]{"BUS", "ONFOOT", "RIDE", "CAR"}) {
            RenamedRequest input = new RenamedRequest();
            input.renamedSelection = RenamedMode.DEFAULT;
            assertEquals(mode, ((RenamedRequest) adapter.replacement(input, mode)).renamedSelection.name());
            assertEquals(mode, ((RenamedRequest) adapter.replacement(null, mode)).renamedSelection.name());
            assertEquals(RenamedMode.DEFAULT, input.renamedSelection);
        }
        assertNull(adapter.replacement(null, "OFF"));
        assertNull(adapter.replacement(null, "invalid"));
    }

    public static class AmbiguousRequest {
        RenamedMode first;
        RenamedMode second;
        Poi start;
        Poi end;
    }
    public static void ambiguousField(AmbiguousRequest request, RenamedCallback callback) {}
    @Test public void rejectsMultipleRouteFields() {
        assertThrows(ReflectiveOperationException.class, () -> RouteAdapter.validate(
                getClass().getMethod("ambiguousField", AmbiguousRequest.class, RenamedCallback.class)));
    }

    public enum ChangedMode {
        DEFAULT(-1), CAR(0), BUS(2), ONFOOT(1), RIDE(3);
        final int value;
        ChangedMode(int value) { this.value = value; }
        public int number() { return value; }
    }
    public static class ChangedRequest { ChangedMode route; Poi start; Poi end; }
    public static class ChangedResult { ChangedMode route; ChangedResult(ChangedMode mode) { route = mode; } }
    public interface ChangedCallback { void done(ChangedResult result); }
    public static void changedSemantics(ChangedRequest input, ChangedCallback callback) {}
    @Test public void rejectsChangedEnumSemantics() {
        assertThrows(ReflectiveOperationException.class, () -> RouteAdapter.validate(
                getClass().getMethod("changedSemantics", ChangedRequest.class, ChangedCallback.class)));
    }

    @Test public void rejectsMissingAndAmbiguousMethodMatches() throws Exception {
        Map<String, RouteAdapter> matches = new LinkedHashMap<>();
        assertThrows(NoSuchMethodException.class, () -> RouteLocator.unique(matches));
        RouteAdapter adapter = adapter();
        matches.put("one", adapter);
        assertSame(adapter, RouteLocator.unique(matches));
        matches.put("two", adapter);
        assertThrows(NoSuchMethodException.class, () -> RouteLocator.unique(matches));
    }

    @Test public void invalidatesCacheForBaseSplitAndInstallPathChanges() throws Exception {
        Path dir = Files.createTempDirectory("route-cache-test");
        try {
            Path base = Files.write(dir.resolve("base.apk"), new byte[]{1});
            String initial = RouteLocator.fingerprint(List.of(base.toString()));
            assertEquals(initial, RouteLocator.fingerprint(List.of(base.toString())));
            Files.write(base, new byte[]{1, 2});
            String updated = RouteLocator.fingerprint(List.of(base.toString()));
            assertNotEquals(initial, updated);
            Path split = Files.write(dir.resolve("split.apk"), new byte[]{3});
            assertNotEquals(updated, RouteLocator.fingerprint(List.of(base.toString(), split.toString())));
            Path installed = Files.move(base, dir.resolve("new-base.apk"));
            assertNotEquals(updated, RouteLocator.fingerprint(List.of(installed.toString())));
        } finally {
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) Files.delete(file);
            }
            Files.delete(dir);
        }
    }
}
