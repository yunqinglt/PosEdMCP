package dev.posedmcp.fixture;
public final class VisibleMarker {
 public static <T> T capture(java.util.function.Supplier<T> operation) { return operation.get(); }
}
