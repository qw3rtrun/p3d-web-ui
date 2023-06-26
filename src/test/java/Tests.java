import java.util.Map;
import java.util.stream.Collectors;

public class Tests {
    public static void main(String[] args) {
        Map.of();
        "a".chars().mapToObj(x -> "A").collect(Collectors.joining());
    }
}
