package risk;

import java.util.List;
import java.util.Map;

/** Every method contains a nested lookup that must NOT be rewritten to a HashMap. */
public class Rules {
    private int counter;

    /** floating-point == is not map-key equality (NaN, -0.0) */
    public int matchByScore(List<Item> left, List<Item> right, Map<Integer, Item> out) {
        int n = 0;
        for (Item a : left) {
            for (Item b : right) {
                if (a.getScore() == b.getScore()) {
                    out.put(a.getId(), b);
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** reference == on strings compares identity */
    public int matchByCodeIdentity(List<Item> left, List<Item> right, Map<Integer, Item> out) {
        int n = 0;
        for (Item a : left) {
            for (Item b : right) {
                if (a.getCode() == b.getCode()) {
                    out.put(a.getId(), b);
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** the scanned list grows inside the enclosing loop: an index would go stale */
    public int matchWhileGrowing(List<Item> left, List<Item> right) {
        int n = 0;
        for (Item a : left) {
            for (Item b : right) {
                if (a.getId() == b.getId()) {
                    right.add(a);
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** the lookup value has a side effect (counter) and is not a plain accessor */
    public int matchWithSideEffect(List<Item> left, List<Item> right, Map<Integer, Item> out) {
        int n = 0;
        for (Item a : left) {
            for (Item b : right) {
                if (b.getId() == a.nextRank() + bump()) {
                    out.put(a.getId(), b);
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** the scan also counts comparisons, so it is more than a lookup */
    public int matchAndCount(List<Item> left, List<Item> right) {
        int comparisons = 0;
        for (Item a : left) {
            for (Item b : right) {
                comparisons++;
                if (a.getId() == b.getId()) {
                    break;
                }
            }
        }
        return comparisons;
    }

    private int bump() {
        return counter++;
    }
}
