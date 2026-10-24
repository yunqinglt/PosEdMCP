package android.content.pm;
import java.util.List;
public final class ParceledListSlice {
    private final List<?> values;
    public ParceledListSlice(List<?> values) { this.values = values; }
    public List<?> getList() { return values; }
}
