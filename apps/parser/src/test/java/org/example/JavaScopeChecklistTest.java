package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * One test per bullet of the case's mandatory Java scope (slide 7, sections 1-4) and bonus scope (slide 8).
 * Every fragment must (a) be valid Java 21 for {@code javac}, (b) parse without errors and without any construct
 * degrading to {@code unknown}, (c) give every method with a body a CFG and an analysis, (d) contain the AST kinds
 * the bullet names, and (e) resolve the JDK types the bullet names (fully qualified in the output).
 * It shows the parser handles each construct; behaviour of the generated programs is covered by the Docker e2e suite.
 */
class JavaScopeChecklistTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Item(String bullet, String source, Set<String> kinds, Set<String> text, Predicate<JsonNode> also) {
        static Item of(String bullet, String source, String kinds, String text) {
            return new Item(bullet, source, split(kinds), split(text), n -> true);
        }

        Item with(Predicate<JsonNode> extra) {
            return new Item(bullet, source, kinds, text, extra);
        }

        private static Set<String> split(String s) {
            Set<String> out = new HashSet<>();
            for (String part : s.split(" ")) {
                if (!part.isBlank()) {
                    out.add(part);
                }
            }
            return out;
        }
    }

    private static final String MAIN = "public static void main(String[] args) {";

    // ---------------------------------------------------------------- slide 7

    private static List<Item> section1() {
        return List.of(
                Item.of("1 Язык и java.lang: пользовательские классы и интерфейсы",
                        "interface Shape { double area(); }\nabstract class Base implements Shape { abstract String name(); }\n"
                                + "class Circle extends Base { double r = 2; public double area() { return Math.PI * r * r; } String name() { return \"c\"; } }\n"
                                + "class Fragment { " + MAIN + " Shape s = new Circle(); System.out.println(s.area()); } }",
                        "class interface method field", "Shape Circle"),
                Item.of("1 Язык и java.lang: примитивы, обёртки, массивы",
                        "class Fragment { " + MAIN + " int a = 1; long b = 2L; double c = 3.5; boolean d = true; char e = 'x'; byte f = 1; short g = 2; float h = 1f;\n"
                                + "Integer i = a; Long j = b; Double k = c; Boolean l = d; Character m = e;\nint[] arr = new int[3]; int[][] grid = new int[2][2]; String[] names = {\"a\"};\n"
                                + "arr[0] = a; grid[1][1] = i; System.out.println(arr.length + names[0] + j + k + l + m + g + f + h); } }",
                        "variable_declaration expression", "java.lang.Integer java.lang.Long java.lang.Double java.lang.Boolean java.lang.Character int[] int[][]"),
                Item.of("1 Язык и java.lang: арифметика, логика, сравнение, приведение",
                        "class Fragment { " + MAIN + " int a = 7, b = 2; double q = (double) a / b; int r = a % b + (a << 1) - (a >> 1) + (a & b) + (a | b) + (a ^ b);\n"
                                + "boolean x = a > b && b != 0 || !(a == b); long widened = a; int narrowed = (int) widened; Object o = a; Integer back = (Integer) o;\n"
                                + "int t = x ? a : b; a++; --b; a += 2; System.out.println(q + r + narrowed + back + t); } }",
                        "variable_declaration expression", "java.lang.Object"),
                Item.of("1 Язык и java.lang: if/else, switch, циклы, break/continue/return",
                        "class Fragment { static int f(int n, String s) {\nif (n < 0) { return -1; } else if (n == 0) { return 0; } else { n++; }\n"
                                + "switch (n) { case 1: n += 1; break; case 2: case 3: n += 2; break; default: n = 0; }\n"
                                + "int day = switch (s) { case \"a\" -> 1; case \"b\" -> { yield 2; } default -> 3; };\n"
                                + "for (int i = 0; i < n; i++) { if (i == 2) continue; if (i == 5) break; }\nfor (String x : new String[] {\"p\"}) { n += x.length(); }\n"
                                + "while (n > 10) { n--; }\ndo { n++; } while (n < 3);\nouter: for (int i = 0; i < 3; i++) { for (int j = 0; j < 3; j++) { if (j == 1) continue outer; if (i == 2) break outer; } }\n"
                                + "return n + day; }\n" + MAIN + " System.out.println(f(3, \"a\")); } }",
                        "if switch case for foreach while do_while break continue return labeled", ""),
                Item.of("1 Язык и java.lang: методы, конструкторы, поля, static",
                        "class Fragment { static int counter; static final int LIMIT = 10; private final String id; int size;\nstatic { counter = 1; }\n"
                                + "Fragment(String id) { this(id, 0); }\nFragment(String id, int size) { this.id = id; this.size = size; counter++; }\n"
                                + "static int next() { return counter++; }\nString id() { return id; }\n" + MAIN + " System.out.println(next() + new Fragment(\"x\").id()); } }",
                        "constructor method field initializer this_call", "")
                        .with(root -> countKind(root.get("types"), "constructor") == 2),
                Item.of("1 Язык и java.lang: try/catch/finally и throw",
                        "class Fragment { static int f(String s) {\ntry { return Integer.parseInt(s); } catch (NumberFormatException e) { throw new IllegalArgumentException(\"bad: \" + s, e); } finally { System.out.println(\"done\"); } }\n"
                                + MAIN + " try { f(\"x\"); } catch (IllegalArgumentException | IllegalStateException e) { System.err.println(e.getMessage()); } } }",
                        "try catch throw", "java.lang.NumberFormatException java.lang.IllegalArgumentException")
                        .with(root -> any(root, n -> "try".equals(n.path("kind").asText()) && n.has("finally") && !n.get("finally").isNull())),
                Item.of("1 Язык и java.lang: Object, String, Math, System и другие базовые классы",
                        "class Fragment { @Override public String toString() { return \"F\"; } @Override public boolean equals(Object o) { return o instanceof Fragment; } @Override public int hashCode() { return 1; }\n"
                                + MAIN + " String s = \"Hello\"; System.out.println(s.length() + s.substring(1).toUpperCase() + s.charAt(0) + Math.max(1, 2) + Math.abs(-3.0) + Math.sqrt(4)\n"
                                + "+ System.currentTimeMillis() + new StringBuilder().append(1).toString() + Integer.valueOf(3) + String.valueOf(2) + Objects_hash()); }\n"
                                + "static int Objects_hash() { return java.util.Objects.hash(1, 2); } }",
                        "method_call", "java.lang.Math java.lang.String java.lang.System java.lang.StringBuilder java.lang.Object"));
    }

    private static List<Item> section2() {
        return List.of(
                Item.of("2 Консольный ввод-вывод: System.in, System.out, System.err",
                        "import java.io.*;\nclass Fragment { " + MAIN + " BufferedReader in = new BufferedReader(new InputStreamReader(System.in)); System.out.println(\"out\"); System.err.println(\"err\"); System.out.print('c'); } }",
                        "method_call constructor_call", "java.io.BufferedReader java.io.InputStreamReader java.io.PrintStream"),
                Item.of("2 Консольный ввод-вывод: Scanner и Formatter",
                        "import java.util.*;\nclass Fragment { " + MAIN + " Scanner sc = new Scanner(System.in); while (sc.hasNextInt()) { int n = sc.nextInt(); System.out.println(n); }\n"
                                + "if (sc.hasNextLine()) { sc.nextLine(); }\nFormatter f = new Formatter(); f.format(\"%5.2f|%-4s|%03d%n\", 3.14159, \"ab\", 7); System.out.print(f); f.close(); sc.close(); } }",
                        "constructor_call method_call", "java.util.Scanner java.util.Formatter"),
                Item.of("2 Консольный ввод-вывод: String.format и printf",
                        "class Fragment { " + MAIN + " String s = String.format(\"%d %s %.3f %x %c %b %e\", 5, \"x\", 2.5, 255, 'c', true, 1234.5); System.out.println(s);\n"
                                + "System.out.printf(\"%s=%d%n\", \"k\", 3); System.err.printf(\"%08.3f%n\", 3.14159); } }",
                        "method_call", "java.lang.String printf"),
                Item.of("2 Консольный ввод-вывод: StandardCharsets и кириллица",
                        "import java.io.*;\nimport java.nio.charset.StandardCharsets;\nclass Fragment { " + MAIN + " PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);\n"
                                + "String s = \"Привет, мир! Ёё\"; out.println(s + s.length()); byte[] b = s.getBytes(StandardCharsets.UTF_8);\n"
                                + "out.println(new String(b, StandardCharsets.UTF_8)); } }",
                        "method_call constructor_call", "java.nio.charset.StandardCharsets Привет, мир!"));
    }

    private static List<Item> section3() {
        return List.of(
                Item.of("3 Коллекции java.util: Collection и Iterable",
                        "import java.util.*;\nclass Fragment { static int count(Iterable<String> items) { int n = 0; for (String s : items) { n += s.length(); } return n; }\n"
                                + "static boolean has(Collection<String> c, String s) { return c.contains(s) && !c.isEmpty() && c.size() > 0; }\n"
                                + MAIN + " List<String> l = new ArrayList<>(); l.add(\"a\"); System.out.println(count(l) + \"\" + has(l, \"a\")); } }",
                        "foreach method_call", "java.util.Collection<java.lang.String> java.lang.Iterable<java.lang.String>"),
                Item.of("3 Коллекции java.util: List, Set и Map",
                        "import java.util.*;\nclass Fragment { " + MAIN + " List<Integer> l = new ArrayList<>(); Set<String> s = new HashSet<>(); Map<String, Integer> m = new HashMap<>();\n"
                                + "l.add(1); s.add(\"a\"); m.put(\"k\", 1); m.merge(\"k\", 1, Integer::sum); for (Map.Entry<String, Integer> e : m.entrySet()) { System.out.println(e.getKey() + e.getValue()); }\n"
                                + "System.out.println(l.get(0) + \"\" + s.contains(\"a\") + m.getOrDefault(\"z\", 0)); } }",
                        "foreach method_call", "java.util.List<java.lang.Integer> java.util.Set<java.lang.String> java.util.Map<java.lang.String, java.lang.Integer>"),
                Item.of("3 Коллекции java.util: ArrayList, LinkedList, HashSet, TreeSet, HashMap, TreeMap",
                        "import java.util.*;\nclass Fragment { " + MAIN + " List<Integer> a = new ArrayList<>(); LinkedList<Integer> b = new LinkedList<>(); Set<Integer> c = new HashSet<>(); TreeSet<Integer> d = new TreeSet<>();\n"
                                + "Map<Integer, String> e = new HashMap<>(); TreeMap<Integer, String> f = new TreeMap<>(); b.addFirst(1); b.removeLast(); d.add(3); d.first(); f.put(1, \"x\"); f.firstKey(); a.add(1); c.add(1); e.put(1, \"y\"); } }",
                        "constructor_call", "java.util.ArrayList java.util.LinkedList java.util.HashSet java.util.TreeSet java.util.HashMap java.util.TreeMap"),
                Item.of("3 Коллекции java.util: Iterator, Collections, Arrays, Comparator, Optional",
                        "import java.util.*;\nclass Fragment { " + MAIN + " List<String> l = new ArrayList<>(List.of(\"b\", \"a\")); Iterator<String> it = l.iterator(); while (it.hasNext()) { if (it.next().isEmpty()) it.remove(); }\n"
                                + "Collections.sort(l); Collections.reverse(l); int[] arr = {3, 1}; Arrays.sort(arr); System.out.println(Arrays.toString(arr) + Collections.max(l));\n"
                                + "l.sort(Comparator.comparing(String::length).thenComparing(Comparator.naturalOrder())); Optional<String> first = l.stream().findFirst(); System.out.println(first.orElse(\"none\") + first.isPresent()); } }",
                        "while method_call", "java.util.Iterator<java.lang.String> java.util.Collections java.util.Arrays java.util.Comparator java.util.Optional<java.lang.String>"),
                Item.of("3 Коллекции java.util: List.of, Set.of, Map.of",
                        "import java.util.*;\nclass Fragment { " + MAIN + " List<Integer> l = List.of(1, 2, 3); Set<String> s = Set.of(\"a\", \"b\"); Map<String, Integer> m = Map.of(\"k\", 1, \"j\", 2);\n"
                                + "System.out.println(l.size() + s.size() + m.size()); } }",
                        "method_call", "java.util.List java.util.Set java.util.Map"));
    }

    private static List<Item> section4() {
        return List.of(
                Item.of("4 Исключения: проверяемые и непроверяемые",
                        "import java.io.*;\nclass Fragment { static void read() throws IOException { throw new IOException(\"io\"); }\n"
                                + MAIN + " try { read(); } catch (IOException e) { System.out.println(e.getMessage()); } try { Object o = null; o.toString(); } catch (NullPointerException | ArithmeticException e) { System.out.println(\"unchecked\"); } } }",
                        "throw try catch", "java.io.IOException java.lang.NullPointerException java.lang.ArithmeticException"),
                Item.of("4 Исключения: throws и обязательный catch",
                        "import java.io.*;\nclass Fragment { static void a() throws IOException, InterruptedException { if (System.nanoTime() < 0) throw new IOException(); }\n"
                                + "static void b() { try { a(); } catch (IOException e) { throw new UncheckedIOException(e); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }\n"
                                + MAIN + " b(); } }",
                        "try catch", "java.io.IOException java.lang.InterruptedException java.io.UncheckedIOException")
                        .with(root -> any(root, n -> n.has("throws") && n.get("throws").isArray() && n.get("throws").size() == 2)),
                Item.of("4 Исключения: собственные исключения через наследование",
                        "class AppException extends Exception { final int code; AppException(String m, int code) { super(m); this.code = code; } }\n"
                                + "class NotFound extends AppException { NotFound(String what) { super(what + \" not found\", 404); } }\nclass Broken extends RuntimeException { Broken(String m, Throwable cause) { super(m, cause); } }\n"
                                + "class Fragment { static void find(String s) throws AppException { throw new NotFound(s); }\n" + MAIN
                                + " try { find(\"x\"); } catch (NotFound e) { System.out.println(e.code); } catch (AppException e) { System.out.println(e.getMessage()); } finally { System.out.println(\"end\"); } } }",
                        "class constructor throw try catch super_call", "NotFound AppException"),
                Item.of("4 Исключения: ключевые типы из java.lang, java.util и java.io",
                        "import java.io.*;\nimport java.util.*;\nclass Fragment { " + MAIN + " try { new ArrayList<Integer>().iterator().next(); } catch (NoSuchElementException e) { System.out.println(\"nse\"); }\n"
                                + "try { Object o = \"s\"; Integer i = (Integer) o; } catch (ClassCastException e) { System.out.println(\"cce\"); }\ntry { int[] a = new int[1]; a[2] = 1; } catch (ArrayIndexOutOfBoundsException e) { System.out.println(\"aioobe\"); }\n"
                                + "try { throw new FileNotFoundException(\"f\"); } catch (IOException e) { System.out.println(\"io\"); }\ntry (StringReader r = new StringReader(\"x\")) { r.read(); } catch (IOException e) { } } }",
                        "try catch", "java.util.NoSuchElementException java.lang.ClassCastException java.lang.ArrayIndexOutOfBoundsException java.io.FileNotFoundException java.io.StringReader")
                        .with(root -> any(root, n -> "try".equals(n.path("kind").asText()) && n.path("resources").isArray() && n.get("resources").size() == 1)));
    }

    // ---------------------------------------------------------------- slide 8 (bonus)

    private static List<Item> bonus() {
        return List.of(
                Item.of("8 Бонус: record",
                        "record Point(int x, int y) { Point { if (x < 0) throw new IllegalArgumentException(); } double dist() { return Math.sqrt(x * x + y * y); } static Point origin() { return new Point(0, 0); } }\n"
                                + "class Fragment { " + MAIN + " Point p = new Point(3, 4); System.out.println(p + \" \" + p.dist() + p.x()); } }",
                        "record method constructor", "Point"),
                Item.of("8 Бонус: enum",
                        "enum Level { LOW(1), HIGH(5) { @Override int weight() { return 50; } }; private final int w; Level(int w) { this.w = w; } int weight() { return w; } }\n"
                                + "class Fragment { " + MAIN + " for (Level l : Level.values()) { System.out.println(l + \":\" + l.weight() + l.ordinal()); } switch (Level.valueOf(\"LOW\")) { case LOW -> System.out.println(1); case HIGH -> System.out.println(2); } } }",
                        "enum enum_constant constructor switch", "Level"),
                Item.of("8 Бонус: дженерики",
                        "import java.util.*;\nclass Box<T extends Comparable<T>> { private T v; Box(T v) { this.v = v; } T get() { return v; } boolean bigger(Box<T> o) { return v.compareTo(o.v) > 0; } }\n"
                                + "class Fragment { static <E> List<E> twice(E e) { List<E> l = new ArrayList<>(); l.add(e); l.add(e); return l; } static double sum(List<? extends Number> l) { double s = 0; for (Number n : l) s += n.doubleValue(); return s; }\n"
                                + MAIN + " System.out.println(new Box<>(2).bigger(new Box<>(1)) + \"\" + twice(\"a\") + sum(List.of(1, 2.5))); } }",
                        "class method", "java.lang.Number java.util.List<E>"),
                Item.of("8 Бонус: лямбды и java.util.function",
                        "import java.util.function.*;\nclass Fragment { " + MAIN + " Function<Integer, Integer> sq = x -> x * x; BiFunction<Integer, Integer, Integer> add = (a, b) -> a + b; Supplier<String> sup = () -> \"s\";\n"
                                + "Predicate<String> empty = String::isEmpty; Consumer<String> out = System.out::println; UnaryOperator<String> up = String::toUpperCase; Runnable r = () -> { out.accept(sup.get()); };\n"
                                + "r.run(); System.out.println(sq.apply(3) + add.apply(1, 2) + up.apply(\"a\") + empty.test(\"\")); } }",
                        "expression", "java.util.function.Function<java.lang.Integer, java.lang.Integer> java.util.function.BiFunction java.util.function.Supplier java.util.function.Predicate java.util.function.Consumer -> ::"),
                Item.of("8 Бонус: Stream API и Collectors",
                        "import java.util.*;\nimport java.util.stream.*;\nclass Fragment { " + MAIN + " List<String> words = List.of(\"apple\", \"bob\", \"cat\", \"apple\");\n"
                                + "Map<Integer, List<String>> byLen = words.stream().distinct().collect(Collectors.groupingBy(String::length)); String joined = words.stream().filter(w -> w.length() > 2).map(String::toUpperCase).sorted().collect(Collectors.joining(\",\"));\n"
                                + "int total = IntStream.rangeClosed(1, 5).sum(); long n = words.stream().count(); Optional<String> max = words.stream().max(Comparator.naturalOrder()); System.out.println(byLen + joined + total + n + max.get()); } }",
                        "method_call", "java.util.stream.Stream<java.lang.String> java.util.stream.Collectors java.util.stream.IntStream"));
    }

    // ---------------------------------------------------------------- harness

    @TestFactory
    Stream<DynamicTest> slide7Sections1To4AndSlide8Bonus(@TempDir Path dir) {
        List<Item> all = new ArrayList<>();
        all.addAll(section1());
        all.addAll(section2());
        all.addAll(section3());
        all.addAll(section4());
        all.addAll(bonus());
        return all.stream().map(item -> DynamicTest.dynamicTest(item.bullet(), () -> check(dir, item)));
    }

    @Test
    void checklistCoversEveryBulletOfSlideSeven() {
        // slide 7: 7 + 4 + 5 + 4 bullets (the five collection lines of the case are List/Set/Map, impls, helpers, factories, interfaces)
        assertEquals(7, section1().size());
        assertEquals(4, section2().size());
        assertEquals(5, section3().size());
        assertEquals(4, section4().size());
        assertEquals(5, bonus().size());
    }

    private static void check(Path dir, Item item) throws IOException {
        // (a) the fragment is real Java 21
        List<String> errors = compileErrors(item.source());
        assertTrue(errors.isEmpty(), "javac rejects the fragment: " + errors);

        // (b) parses cleanly, nothing degrades to 'unknown'
        Path file = Files.writeString(dir.resolve("Fragment.java"), item.source());
        JsonNode root = JSON.valueToTree(Main.parse(Files.readString(file), "Fragment.java"));
        assertEquals("completed", root.get("status").asText(), root.get("diagnostics").toString());
        for (JsonNode d : root.get("diagnostics")) {
            assertFalse("error".equals(d.get("severity").asText()), d.toString());
        }
        Set<String> kinds = new HashSet<>();
        collectKinds(root.get("types"), kinds);
        assertFalse(kinds.contains("unknown"), "a construct degraded to 'unknown': " + kinds);

        // (c) every method with a body has a CFG and an analysis
        List<JsonNode> methods = new ArrayList<>();
        collectMethods(root.get("types"), methods);
        assertFalse(methods.isEmpty());
        for (JsonNode m : methods) {
            if (!m.path("body").isNull() && m.has("body")) {
                assertNotNull(m.get("cfg"), "no CFG for " + m.get("id"));
                assertTrue(m.get("cfg").isObject() && m.get("cfg").get("nodes").size() >= 2, "empty CFG for " + m.get("id"));
                assertTrue(m.has("analysis") && m.get("analysis").isObject(), "no analysis for " + m.get("id"));
            }
        }

        // (d) the AST kinds the bullet names
        for (String kind : item.kinds()) {
            assertTrue(kinds.contains(kind), "missing AST kind '" + kind + "' in " + kinds);
        }
        // (e) resolved JDK types / texts the bullet names
        String json = root.toString();
        for (String text : item.text()) {
            assertTrue(json.contains(text), "'" + text + "' not found in the parser output");
        }
        assertTrue(item.also().test(root), "extra structural check failed");
    }

    private static List<String> compileErrors(String text) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Fragment.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
        JavaFileObject sink = new SimpleJavaFileObject(URI.create("mem:///Fragment.class"), JavaFileObject.Kind.CLASS) {
            @Override
            public java.io.OutputStream openOutputStream() {
                return java.io.OutputStream.nullOutputStream();
            }
        };
        var manager = new javax.tools.ForwardingJavaFileManager<>(compiler.getStandardFileManager(null, null, null)) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                    javax.tools.FileObject sibling) {
                return sink;
            }
        };
        compiler.getTask(null, manager, diagnostics, List.of("--release", "21", "-Xlint:none"), null, List.of(file)).call();
        List<String> errors = new ArrayList<>();
        diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                .forEach(d -> errors.add(d.getLineNumber() + ": " + d.getMessage(null)));
        return errors;
    }

    private static void collectKinds(JsonNode n, Set<String> out) {
        if (n.isObject()) {
            if (n.has("kind")) {
                out.add(n.get("kind").asText());
            }
            n.fields().forEachRemaining(e -> collectKinds(e.getValue(), out));
        } else if (n.isArray()) {
            n.forEach(c -> collectKinds(c, out));
        }
    }

    private static void collectMethods(JsonNode members, List<JsonNode> out) {
        if (members.isArray()) {
            for (JsonNode m : members) {
                String kind = m.path("kind").asText();
                if (kind.equals("method") || kind.equals("constructor")) {
                    out.add(m);
                }
                if (m.has("members")) {
                    collectMethods(m.get("members"), out);
                }
                if (m.isObject() && !m.has("members") && m.has("types")) {
                    collectMethods(m.get("types"), out);
                }
            }
        }
    }

    private static boolean any(JsonNode n, Predicate<JsonNode> test) {
        if (n.isObject()) {
            if (test.test(n)) {
                return true;
            }
            var fields = n.fields();
            while (fields.hasNext()) {
                if (any(fields.next().getValue(), test)) {
                    return true;
                }
            }
        } else if (n.isArray()) {
            for (JsonNode c : n) {
                if (any(c, test)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countKind(JsonNode n, String kind) {
        int[] count = {0};
        any(n, x -> {
            if (kind.equals(x.path("kind").asText())) {
                count[0]++;
            }
            return false;
        });
        return count[0];
    }

    @Test
    void methodsInAnonymousClassesAndEnumConstantBodiesAreReportedAsUnanalyzed(@TempDir Path dir) throws IOException {
        String source = "enum Level { LOW, HIGH { @Override int weight() { return 50; } }; int weight() { return 1; } }\n"
                + "class Fragment { interface Op { int apply(int x); }\n"
                + "  static int run() { Op o = new Op() { public int apply(int x) { return x > 1 ? x * 2 : x; } }; return o.apply(3); } }\n";
        JsonNode root = JSON.valueToTree(Main.parse(source, "Fragment.java"));
        assertEquals("completed", root.get("status").asText());
        List<String> listed = new ArrayList<>();
        root.get("methods").forEach(m -> listed.add(m.get("id").asText()));
        assertFalse(listed.stream().anyMatch(id -> id.contains("HIGH")), "not listed: " + listed);
        int notices = 0;
        for (JsonNode d : root.get("diagnostics")) {
            if (d.get("code").asText().equals("unanalyzed_anonymous_body")) {
                notices++;
                assertEquals("info", d.get("severity").asText());
                assertTrue(d.get("source_range").get("line").asInt() >= 1);
            }
        }
        assertEquals(2, notices, "one notice for the enum constant body and one for the anonymous class: " + root.get("diagnostics"));
        // ordinary classes and lambdas stay silent
        JsonNode plain = JSON.valueToTree(Main.parse("class P { int f() { Runnable r = () -> { }; return 1; } }", "P.java"));
        plain.get("diagnostics").forEach(d -> assertFalse(d.get("code").asText().equals("unanalyzed_anonymous_body")));
    }
}
