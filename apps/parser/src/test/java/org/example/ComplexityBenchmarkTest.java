package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The documented benchmark set from docs/COMPLEXITY_ENGINE.md. Each expectation is a grounded result:
 * where the engine cannot justify a bound it must say {@code unknown}.
 */
class ComplexityBenchmarkTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode find(JsonNode node, String methodId) {
        if (node.isObject()) {
            if (node.has("id") && methodId.equals(node.get("id").asText()) && node.has("analysis")
                    && node.get("analysis").isObject()) {
                return node.get("analysis").get("complexity");
            }
            for (JsonNode child : node) {
                JsonNode hit = find(child, methodId);
                if (hit != null) {
                    return hit;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode hit = find(child, methodId);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static JsonNode complexity(String source, String methodId) {
        JsonNode result = find(JSON.valueToTree(Main.parse(source, "Main.java")), methodId);
        assertNotNull(result, methodId);
        return result;
    }

    private static String time(String source, String methodId) {
        return complexity(source, methodId).get("time").asText();
    }

    private static String wrap(String members) {
        return "import java.util.*;\npublic class Main {\n" + members + "\n}\n";
    }

    @Test
    void constantTimeBasicOperations() {
        assertEquals("O(1)", time(wrap("static int f(int a, int b) { int c = a + b; return c * 2 - a; }"), "Main.f(int,int)"));
    }

    @Test
    void linearLoop() {
        assertEquals("O(n)", time(wrap("static int f(int[] xs) { int s = 0; for (int i = 0; i < xs.length; i++) { s += xs[i]; } return s; }"),
                "Main.f(int[])"));
    }

    @Test
    void nestedLoopsGiveQuadraticAndProductBounds() {
        String quadratic = wrap("static int f(int[] xs) { int c = 0; for (int i = 0; i < xs.length; i++) { for (int j = 0; j < xs.length; j++) { c++; } } return c; }");
        assertEquals("O(n^2)", time(quadratic, "Main.f(int[])"));
        String product = wrap("static int f(int[] xs, int[] ys) { int c = 0; for (int x : xs) { for (int y : ys) { c++; } } return c; }");
        assertEquals("O(n*m)", time(product, "Main.f(int[],int[])"));
    }

    @Test
    void logarithmicLoop() {
        assertEquals("O(log n)", time(wrap("static int f(int n) { int c = 0; for (int i = n; i > 1; i = i / 2) { c++; } return c; }"), "Main.f(int)"));
    }

    private static final String BINARY_SEARCH = "static int search(int[] arr, int key) {\n"
            + "  int lo = 0, hi = arr.length - 1;\n"
            + "  while (lo <= hi) {\n"
            + "    int mid = (lo + hi) >>> 1;\n"
            + "    if (arr[mid] < key) lo = mid + 1; else if (arr[mid] > key) hi = mid - 1; else return mid;\n"
            + "  }\n  return -1;\n}";

    @Test
    void binarySearchIsLogarithmicWithItsAssumptionStated() {
        JsonNode c = complexity(wrap(BINARY_SEARCH), "Main.search(int[],int)");
        assertEquals("O(log n)", c.get("time").asText());
        assertEquals("medium", c.get("confidence").asText());
        assertTrue(c.get("assumptions").toString().contains("overflow"));
        assertEquals("jdt-static-cost-model/2", c.get("engine").asText());
        assertTrue(c.get("limitations").size() > 0);
    }

    @Test
    void midpointLoopWithoutGuaranteedProgressStaysUnknown() {
        // the `else` is missing, so a path can leave lo/hi unchanged and loop forever
        String source = wrap("static int search(int[] arr, int key) { int lo = 0, hi = arr.length - 1;\n"
                + "  while (lo <= hi) { int mid = (lo + hi) / 2; if (arr[mid] < key) lo = mid + 1; else if (arr[mid] > key) hi = mid - 1; }\n"
                + "  return -1; }");
        assertEquals("unknown", time(source, "Main.search(int[],int)"));
        // lo = mid (no +1) does not shrink the interval
        source = wrap("static int search(int[] arr, int key) { int lo = 0, hi = arr.length - 1;\n"
                + "  while (lo <= hi) { int mid = (lo + hi) / 2; if (arr[mid] < key) lo = mid; else hi = mid - 1; }\n"
                + "  return -1; }");
        assertEquals("unknown", time(source, "Main.search(int[],int)"));
    }

    @Test
    void recursiveFibonacciIsExponential() {
        String source = wrap("static int fib(int n) { if (n < 2) return n; return fib(n - 1) + fib(n - 2); }");
        JsonNode c = complexity(source, "Main.fib(int)");
        assertEquals("O(2^n)", c.get("time").asText());
        assertTrue(c.get("assumptions").toString().contains("upper bound"));
    }

    @Test
    void otherRecursionShapes() {
        assertEquals("O(n)", time(wrap("static int fact(int n) { if (n <= 1) return 1; return n * fact(n - 1); }"), "Main.fact(int)"));
        assertEquals("O(log n)", time(wrap("static int pow2(int n) { if (n <= 0) return 0; return 1 + pow2(n / 2); }"), "Main.pow2(int)"));
        assertEquals("O(n)", time(wrap("static int sum(int n) { if (n == 0) return 0; return n + sum(n - 1); }"), "Main.sum(int)"));
        // unsupported shapes stay unknown rather than guessed
        assertEquals("unknown", time(wrap("static int bad(int n) { return bad(n - 1); }"), "Main.bad(int)"));
        assertEquals("unknown", time(wrap("static int loopy(int n) { if (n <= 0) return 0; int s = 0; for (int i = 0; i < 3; i++) { s += loopy(n - 1); } return s; }"), "Main.loopy(int)"));
        assertEquals("unknown", time(wrap("static int grow(int n) { if (n <= 0) return 0; return grow(n + 1); }"), "Main.grow(int)"));
    }

    @Test
    void quickSortIsNotClaimed() {
        String source = wrap("static void sort(int[] x, int lo, int hi) {\n"
                + "  if (lo >= hi) return; int p = x[hi], i = lo;\n"
                + "  for (int j = lo; j < hi; j++) { if (x[j] < p) { int t = x[i]; x[i] = x[j]; x[j] = t; i++; } }\n"
                + "  int t = x[i]; x[i] = x[hi]; x[hi] = t; sort(x, lo, i - 1); sort(x, i + 1, hi); }");
        JsonNode c = complexity(source, "Main.sort(int[],int,int)");
        assertEquals("unknown", c.get("time").asText());
        assertEquals("low", c.get("confidence").asText());
    }

    @Test
    void nestedCollectionScansAndStandardLibraryModels() {
        String scans = wrap("static int f(List<Integer> xs, List<Integer> ys) { int c = 0;\n"
                + "  for (Integer x : xs) { for (Integer y : ys) { if (x.equals(y)) c++; } } return c; }");
        assertEquals("O(n*m)", time(scans, "Main.f(List<Integer>,List<Integer>)"));
        // contains() on a List is linear, so a loop around it is quadratic; the same on a HashSet is linear
        String listContains = wrap("static int f(List<Integer> xs, List<Integer> ys) { int c = 0;\n"
                + "  for (Integer x : xs) { if (ys.contains(x)) c++; } return c; }");
        assertEquals("O(n*m)", time(listContains, "Main.f(List<Integer>,List<Integer>)"));
        String setContains = wrap("static int f(List<Integer> xs, Set<Integer> ys) { int c = 0;\n"
                + "  for (Integer x : xs) { if (ys.contains(x)) c++; } return c; }");
        assertEquals("O(n)", time(setContains, "Main.f(List<Integer>,Set<Integer>)"));
        assertEquals("O(n log n)", time(wrap("static void f(List<Integer> xs) { Collections.sort(xs); }"), "Main.f(List<Integer>)"));
        assertEquals("O(log n)", time(wrap("static int f(int[] xs) { return Arrays.binarySearch(xs, 3); }"), "Main.f(int[])"));
    }

    private static JsonNode analyzeProject(Path root, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        return JSON.valueToTree(ProjectAnalyzer.analyze(root, new ArrayList<>(files.keySet()), "8"));
    }

    @Test
    void callsAcrossSourceFilesUseTheCalleesAnalyzedCost(@TempDir Path root) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a/Customer.java", "package a; public class Customer { private final int id; public Customer(int id) { this.id = id; } public int id() { return id; } }\n");
        files.put("a/Search.java", "package a; public class Search {\n"
                + "  public static int find(int[] arr, int key) { int lo = 0, hi = arr.length - 1;\n"
                + "    while (lo <= hi) { int mid = (lo + hi) >>> 1; if (arr[mid] < key) lo = mid + 1; else if (arr[mid] > key) hi = mid - 1; else return mid; }\n"
                + "    return -1; }\n"
                + "  public static int scan(int[] arr) { int s = 0; for (int i = 0; i < arr.length; i++) { s += arr[i]; } return s; }\n"
                + "}\n");
        files.put("b/Report.java", "package b; import a.*; import java.util.*;\n"
                + "public class Report {\n"
                + "  int total(List<Customer> cs) { int t = 0; for (Customer c : cs) { t += c.id(); } return t; }\n"
                + "  int lookups(int[] sorted, int[] keys) { int hits = 0; for (int k : keys) { if (Search.find(sorted, k) >= 0) hits++; } return hits; }\n"
                + "  int rescans(int[] data, int[] keys) { int s = 0; for (int k : keys) { s += Search.scan(data); } return s; }\n"
                + "  int mystery(int[] data) { return Helper.unknown(data); }\n"
                + "}\n");
        files.put("b/Helper.java", "package b; public class Helper { public static int unknown(int[] d) { return d.hashCode() > 0 ? 1 : someNative(d); } static native int someNative(int[] d); }\n");
        JsonNode result = analyzeProject(root, files);

        // getter in another file is O(1), so the loop stays linear
        assertEquals("O(n)", find(result, "b.Report.total(List<Customer>)").get("time").asText());
        // callee cost O(log |sorted|) inside a loop over keys: O(keys * log sorted)
        JsonNode lookups = find(result, "b.Report.lookups(int[],int[])");
        assertEquals("O(n log m)", lookups.get("time").asText());
        assertTrue(lookups.get("assumptions").toString().contains("argument sizes"));
        // linear callee in a loop over keys
        assertEquals("O(n*m)", find(result, "b.Report.rescans(int[],int[])").get("time").asText());
        // callee with an unmodelled native call: no fabricated estimate
        assertEquals("unknown", find(result, "b.Report.mystery(int[])").get("time").asText());
    }
}
