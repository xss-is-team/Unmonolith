package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CfgTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Query helper over one method's cfg. Nodes are addressed by their source text. */
    private static final class Graph {
        final JsonNode cfg;

        Graph(String methodBody) {
            this(methodBody, "");
        }

        Graph(String methodBody, String extraMembers) {
            this(JSON.valueToTree(Main.parse(
                    "class Example {\n" + extraMembers + "\n void m(int a, int b, int n, boolean c, boolean x, boolean y,"
                            + " java.util.List<String> xs) throws Exception {\n" + methodBody + "\n}\n}\n",
                    "Example.java")), "Example.m");
        }

        Graph(JsonNode root, String methodPrefix) {
            JsonNode found = null;
            for (JsonNode member : root.at("/types/0/members")) {
                if (member.path("id").asText().startsWith(methodPrefix)) {
                    found = member.get("cfg");
                }
            }
            this.cfg = found;
            assertEquals("m0", cfg.get("entry").asText().split("\\.")[0], "revision-local id prefix");
        }

        List<JsonNode> all(String text) {
            List<JsonNode> list = new ArrayList<>();
            for (JsonNode n : cfg.get("nodes")) {
                if (text.equals(n.path("text").asText(null))) {
                    list.add(n);
                }
            }
            return list;
        }

        JsonNode node(String text) {
            List<JsonNode> list = all(text);
            if (list.size() != 1) {
                fail("expected exactly one node with text '" + text + "' but found " + list.size() + "\n" + dump());
            }
            return list.get(0);
        }

        JsonNode entry() {
            return byId(cfg.get("entry").asText());
        }

        JsonNode exit() {
            return byId(cfg.get("exit").asText());
        }

        JsonNode byId(String id) {
            for (JsonNode n : cfg.get("nodes")) {
                if (n.get("id").asText().equals(id)) {
                    return n;
                }
            }
            return fail("no node " + id);
        }

        List<JsonNode> out(JsonNode n) {
            List<JsonNode> list = new ArrayList<>();
            for (JsonNode e : cfg.get("edges")) {
                if (e.get("from").asText().equals(n.get("id").asText())) {
                    list.add(e);
                }
            }
            return list;
        }

        List<JsonNode> in(JsonNode n) {
            List<JsonNode> list = new ArrayList<>();
            for (JsonNode e : cfg.get("edges")) {
                if (e.get("to").asText().equals(n.get("id").asText())) {
                    list.add(e);
                }
            }
            return list;
        }

        JsonNode edge(JsonNode from, JsonNode to, String kind) {
            for (JsonNode e : out(from)) {
                if (e.get("to").asText().equals(to.get("id").asText()) && e.get("kind").asText().equals(kind)) {
                    return e;
                }
            }
            return fail("missing edge " + from.path("text") + " -" + kind + "-> " + to.path("text") + "\n" + dump());
        }

        JsonNode edge(String from, String to, String kind) {
            return edge(node(from), node(to), kind);
        }

        boolean hasEdge(JsonNode from, JsonNode to) {
            return out(from).stream().anyMatch(e -> e.get("to").asText().equals(to.get("id").asText()));
        }

        /** The only successor of n (asserts there is exactly one outgoing edge). */
        JsonNode only(JsonNode n) {
            List<JsonNode> edges = out(n);
            assertEquals(1, edges.size(), "successors of " + n.path("text") + "\n" + dump());
            return byId(edges.get(0).get("to").asText());
        }

        Set<String> reachable() {
            Set<String> seen = new HashSet<>();
            List<String> work = new ArrayList<>(List.of(cfg.get("entry").asText()));
            while (!work.isEmpty()) {
                String id = work.remove(work.size() - 1);
                if (seen.add(id)) {
                    for (JsonNode e : out(byId(id))) {
                        work.add(e.get("to").asText());
                    }
                }
            }
            return seen;
        }

        boolean reaches(JsonNode from, JsonNode to) {
            Set<String> seen = new HashSet<>();
            List<String> work = new ArrayList<>(List.of(from.get("id").asText()));
            while (!work.isEmpty()) {
                String id = work.remove(work.size() - 1);
                if (id.equals(to.get("id").asText())) {
                    return true;
                }
                if (seen.add(id)) {
                    for (JsonNode e : out(byId(id))) {
                        work.add(e.get("to").asText());
                    }
                }
            }
            return false;
        }

        boolean isReachable(JsonNode n) {
            return reachable().contains(n.get("id").asText());
        }

        String dump() {
            StringBuilder sb = new StringBuilder();
            for (JsonNode e : cfg.get("edges")) {
                sb.append(label(byId(e.get("from").asText()))).append(" -").append(e.get("kind").asText())
                        .append(e.path("back").asBoolean() ? "*" : "").append("-> ")
                        .append(label(byId(e.get("to").asText()))).append('\n');
            }
            return sb.toString();
        }

        private static String label(JsonNode n) {
            return n.get("id").asText() + "[" + n.get("kind").asText() + ":" + n.path("text").asText("") + "]";
        }
    }

    @Test
    void sequentialStatements() {
        JsonNode root = JSON.valueToTree(Main.parse("class Example { void m() { int a = 1; a++; foo(a); } void foo(int x) { } }", "E.java"));
        Graph g = new Graph(root, "Example.m");

        JsonNode decl = g.node("int a = 1;");
        JsonNode inc = g.node("a++;");
        JsonNode call = g.node("foo(a);");
        assertEquals(1, root.get("protocol_version").asInt());
        assertEquals(5, g.cfg.get("nodes").size());
        assertEquals(4, g.cfg.get("edges").size());
        assertEquals("next", g.edge(g.entry(), decl, "next").get("kind").asText());
        g.edge(decl, inc, "next");
        g.edge(inc, call, "next");
        g.edge(call, g.exit(), "next");
        assertEquals("expression", call.get("statement_kind").asText());
        assertEquals("variable_declaration", decl.get("statement_kind").asText());
        assertEquals(1, decl.at("/source_range/line").asInt());
    }

    @Test
    void ifWithoutElse() {
        Graph g = new Graph("if (c) { a = 1; } b = 2;");

        JsonNode cond = g.node("c");
        JsonNode then = g.node("a = 1;");
        JsonNode after = g.node("b = 2;");
        assertEquals("branch", cond.get("kind").asText());
        g.edge(cond, then, "true");
        g.edge(cond, after, "false");
        g.edge(then, after, "next");
        assertEquals(2, g.in(after).size());
        assertEquals(2, g.out(cond).size());
    }

    @Test
    void ifElseMerges() {
        Graph g = new Graph("if (c) { a = 1; } else { a = 2; } b = 3;");

        JsonNode merge = g.node("b = 3;");
        g.edge("c", "a = 1;", "true");
        g.edge("c", "a = 2;", "false");
        g.edge("a = 1;", "b = 3;", "next");
        g.edge("a = 2;", "b = 3;", "next");
        assertEquals(2, g.in(merge).size());
        assertEquals(1, g.in(g.exit()).size(), "exit is reached only through the merge node");
        assertEquals(g.exit().get("id"), g.only(merge).get("id"));
    }

    @Test
    void nestedIfConditions() {
        Graph g = new Graph("if (x) { if (y) { a = 1; } } else if (c) { a = 2; } b = 3;");

        g.edge("x", "y", "true");
        g.edge("x", "c", "false");
        g.edge("y", "a = 1;", "true");
        g.edge("y", "b = 3;", "false");
        g.edge("c", "a = 2;", "true");
        g.edge("c", "b = 3;", "false");
        g.edge("a = 1;", "b = 3;", "next");
        g.edge("a = 2;", "b = 3;", "next");
        assertEquals(4, g.in(g.node("b = 3;")).size());
    }

    @Test
    void whileLoopHasBackEdge() {
        Graph g = new Graph("while (c) { a++; } b = 1;");

        JsonNode head = g.node("c");
        JsonNode body = g.node("a++;");
        g.edge(head, body, "true");
        g.edge(head, g.node("b = 1;"), "false");
        JsonNode back = g.edge(body, head, "next");
        assertTrue(back.path("back").asBoolean());
        assertFalse(g.edge(g.entry(), head, "next").has("back"));
        long backEdges = 0;
        for (JsonNode e : g.cfg.get("edges")) {
            backEdges += e.path("back").asBoolean() ? 1 : 0;
        }
        assertEquals(1, backEdges, "only the loop-closing edge is a back edge");
    }

    @Test
    void forLoopUpdateAndContinue() {
        Graph g = new Graph("for (int i = 0; i < n; i++) { if (x) continue; a++; } b = 1;");

        JsonNode init = g.node("int i = 0");
        JsonNode cond = g.node("i < n");
        JsonNode update = g.node("i++");
        JsonNode cont = g.node("continue;");
        g.edge(g.entry(), init, "next");
        g.edge(init, cond, "next");
        g.edge(cond, g.node("x"), "true");
        g.edge(cond, g.node("b = 1;"), "false");
        g.edge(cont, update, "continue");
        g.edge("a++;", "i++", "next");
        assertTrue(g.edge(update, cond, "next").path("back").asBoolean());
        assertEquals("for_init", init.get("statement_kind").asText());
        assertEquals("for_update", update.get("statement_kind").asText());
        assertEquals(1, g.out(cont).size(), "continue has no fall-through edge");
    }

    @Test
    void enhancedForLoop() {
        Graph g = new Graph("for (String s : xs) { a++; } b = 1;");

        JsonNode head = g.node("String s : xs");
        assertEquals("foreach", head.get("statement_kind").asText());
        g.edge(head, g.node("a++;"), "true");
        g.edge(head, g.node("b = 1;"), "false");
        assertTrue(g.edge("a++;", "String s : xs", "next").path("back").asBoolean());
    }

    @Test
    void doWhileRunsBodyFirst() {
        Graph g = new Graph("do { a++; } while (c); b = 1;");

        JsonNode body = g.node("a++;");
        JsonNode cond = g.node("c");
        g.edge(g.entry(), body, "next");
        g.edge(body, cond, "next");
        assertTrue(g.edge(cond, body, "true").path("back").asBoolean());
        g.edge(cond, g.node("b = 1;"), "false");
    }

    @Test
    void nestedLoopsBreakAndContinueTargetInnermost() {
        Graph g = new Graph("""
                while (c) {
                    while (x) {
                        if (y) break;
                        if (c) continue;
                        a++;
                    }
                    b++;
                }
                n = 0;
                """);

        List<JsonNode> conds = g.all("c");
        JsonNode outer = conds.stream().filter(n -> g.out(n).stream().anyMatch(e -> e.get("kind").asText().equals("false")
                && g.byId(e.get("to").asText()).path("text").asText().equals("n = 0;"))).findFirst().orElseThrow();
        JsonNode inner = g.node("x");
        g.edge(g.node("break;"), g.node("b++;"), "break");
        g.edge(g.node("continue;"), inner, "continue");
        g.edge(inner, g.node("b++;"), "false");
        assertTrue(g.edge(g.node("b++;"), outer, "next").path("back").asBoolean());
        assertTrue(g.edge(g.node("a++;"), inner, "next").path("back").asBoolean());
        assertNotEquals(outer.get("id"), inner.get("id"));
    }

    @Test
    void labeledBreakAndContinue() {
        Graph g = new Graph("""
                outer:
                for (int i = 0; i < n; i++) {
                    for (String s : xs) {
                        if (x) continue outer;
                        if (y) break outer;
                        a++;
                    }
                    b++;
                }
                n = 0;
                """);

        g.edge("continue outer;", "i++", "continue");
        g.edge("break outer;", "n = 0;", "break");
        assertFalse(g.hasEdge(g.node("break outer;"), g.node("b++;")));
        assertFalse(g.hasEdge(g.node("continue outer;"), g.node("String s : xs")));
        g.edge("String s : xs", "b++;", "false");
    }

    @Test
    void switchFallsThroughWithoutBreak() {
        Graph g = new Graph("""
                switch (a) {
                    case 1: b = 1;
                    case 2: b = 2; break;
                    default: b = 3;
                }
                n = 0;
                """);

        JsonNode sw = g.node("a");
        assertEquals("switch", sw.get("kind").asText());
        assertEquals("1", g.edge(sw, g.node("b = 1;"), "case").get("label").asText());
        assertEquals("2", g.edge(sw, g.node("b = 2;"), "case").get("label").asText());
        g.edge(sw, g.node("b = 3;"), "default");
        g.edge("b = 1;", "b = 2;", "next");
        g.edge("b = 2;", "break;", "next");
        g.edge("break;", "n = 0;", "break");
        g.edge("b = 3;", "n = 0;", "next");
        assertEquals(3, g.out(sw).size(), "a default exists, so the selector cannot skip the switch");
    }

    @Test
    void switchWithoutDefaultCanSkipAndArrowCasesDoNotFallThrough() {
        Graph g = new Graph("""
                switch (a) {
                    case 1 -> b = 1;
                    case 2, 3 -> { b = 2; }
                }
                n = 0;
                """);

        JsonNode sw = g.node("a");
        g.edge(sw, g.node("b = 1;"), "case");
        assertEquals("2, 3", g.edge(sw, g.node("b = 2;"), "case").get("label").asText());
        g.edge(sw, g.node("n = 0;"), "default");
        g.edge("b = 1;", "n = 0;", "next");
        assertFalse(g.hasEdge(g.node("b = 1;"), g.node("b = 2;")));
        assertEquals("expression", g.node("b = 1;").get("statement_kind").asText());
    }

    @Test
    void returnInsideBranchEndsThatPath() {
        Graph g = new Graph("if (c) { return; } a = 1; return; \n b = 2;");

        List<JsonNode> returns = g.all("return;");
        assertEquals(2, returns.size());
        for (JsonNode r : returns) {
            assertEquals(1, g.out(r).size());
            g.edge(r, g.exit(), "return");
        }
        assertEquals(3, g.in(g.exit()).size(), "two returns plus the dead statement falling off the end");
        assertEquals(2, g.in(g.exit()).stream().filter(e -> g.isReachable(g.byId(e.get("from").asText()))).count());
        JsonNode dead = g.node("b = 2;");
        assertFalse(g.isReachable(dead));
        assertFalse(dead.get("reachable").asBoolean());
        assertTrue(g.isReachable(g.exit()));
        assertFalse(g.node("a = 1;").has("reachable"));
    }

    @Test
    void tryCatchThrow() {
        Graph g = new Graph("""
                try {
                    throw new IllegalStateException();
                } catch (IllegalStateException e) {
                    a = 1;
                }
                b = 2;
                try {
                    throw new Exception();
                } catch (RuntimeException e) {
                    a = 3;
                }
                """);

        // thrown type is certainly caught by the first handler: no edge out of the method
        JsonNode throwNode = g.node("throw new IllegalStateException();");
        JsonNode catchNode = g.node("catch (IllegalStateException e)");
        assertEquals(1, g.out(throwNode).size());
        assertEquals("IllegalStateException", g.edge(throwNode, catchNode, "exception").get("label").asText());
        g.edge(catchNode, g.node("a = 1;"), "next");
        g.edge("a = 1;", "b = 2;", "next");

        // a checked Exception is not necessarily a RuntimeException: it may escape
        JsonNode second = g.node("throw new Exception();");
        g.edge(second, g.node("catch (RuntimeException e)"), "exception");
        g.edge(second, g.exit(), "exception");
        assertTrue(g.isReachable(g.node("a = 3;")));
    }

    @Test
    void callInsideTryGetsExceptionEdgeToCatchAndOut() {
        Graph g = new Graph("try { xs.clear(); } catch (IllegalStateException e) { a = 1; } b = 2;");

        JsonNode call = g.node("xs.clear();");
        g.edge(call, g.node("b = 2;"), "next");
        g.edge(call, g.node("catch (IllegalStateException e)"), "exception");
        g.edge(call, g.exit(), "exception");
    }

    @Test
    void catchOfExceptionStopsPropagation() {
        Graph g = new Graph("try { xs.clear(); } catch (Exception e) { a = 1; } b = 2;");

        JsonNode call = g.node("xs.clear();");
        g.edge(call, g.node("catch (Exception e)"), "exception");
        assertTrue(g.out(call).stream().noneMatch(e -> e.get("kind").asText().equals("exception")
                && e.get("to").asText().equals(g.exit().get("id").asText())));
    }

    @Test
    void returnGoesThroughFinallyClone() {
        Graph g = new Graph("try { return; } finally { a = 1; }");

        JsonNode ret = g.node("return;");
        JsonNode cleanup = g.only(ret);
        assertEquals("a = 1;", cleanup.get("text").asText());
        assertEquals("return", g.out(ret).get(0).get("kind").asText());
        g.edge(cleanup, g.exit(), "next");
        assertTrue(g.isReachable(cleanup));
        // the normal-completion clone exists but can never run because the body always returns
        List<JsonNode> clones = g.all("a = 1;");
        assertEquals(2, clones.size());
        assertEquals(1, clones.stream().filter(n -> !g.isReachable(n)).count());
    }

    @Test
    void breakAndContinueGoThroughFinally() {
        Graph g = new Graph("""
                while (c) {
                    try {
                        if (x) break;
                        if (y) continue;
                        a++;
                    } finally {
                        b++;
                    }
                }
                n = 0;
                """);

        JsonNode afterBreak = g.only(g.node("break;"));
        JsonNode afterContinue = g.only(g.node("continue;"));
        assertEquals("b++;", afterBreak.get("text").asText());
        assertEquals("b++;", afterContinue.get("text").asText());
        assertNotEquals(afterBreak.get("id"), afterContinue.get("id"));
        // break leaves the loop after the finally; continue returns to the loop condition
        assertEquals("n = 0;", g.only(afterBreak).get("text").asText());
        JsonNode head = g.all("c").get(0);
        assertEquals(head.get("id"), g.only(afterContinue).get("id"));
        assertTrue(g.edge(afterContinue, head, "next").path("back").asBoolean());
    }

    @Test
    void nestedTryFinallyRunsInnerThenOuter() {
        Graph g = new Graph("try { try { return; } finally { a = 1; } } finally { b = 2; }");

        JsonNode inner = g.only(g.node("return;"));
        assertEquals("a = 1;", inner.get("text").asText());
        JsonNode outer = g.only(inner);
        assertEquals("b = 2;", outer.get("text").asText());
        assertEquals(g.exit().get("id"), g.only(outer).get("id"));
    }

    @Test
    void exceptionInTryWithFinallyRethrowsAfterFinally() {
        Graph g = new Graph("try { xs.clear(); } finally { a = 1; }");

        JsonNode call = g.node("xs.clear();");
        // normal path: call -> finally clone -> exit; exceptional path: call -> finally clone -> rethrow -> exit
        g.edge(call, g.all("a = 1;").stream().filter(n -> g.hasEdge(n, g.exit()))
                .findFirst().orElseThrow(), "next");
        JsonNode exceptionalEdge = g.out(call).stream().filter(e -> e.get("kind").asText().equals("exception"))
                .findFirst().orElseThrow();
        assertEquals("finally", exceptionalEdge.get("label").asText());
        JsonNode exceptionalClone = g.byId(exceptionalEdge.get("to").asText());
        assertEquals("a = 1;", exceptionalClone.get("text").asText());
        JsonNode rethrow = g.only(exceptionalClone);
        assertEquals("rethrow", rethrow.get("kind").asText());
        g.edge(rethrow, g.exit(), "exception");
        assertTrue(g.isReachable(rethrow));
        assertEquals(2, g.all("a = 1;").size());
    }

    @Test
    void cyrillicSourceUsesUtf16Offsets() {
        String source = """
                class Example {
                    void m() {
                        String привет = "мир 😀";
                        if (привет.isEmpty()) { привет = "пусто"; }
                    }
                }
                """;
        Graph g = new Graph(JSON.valueToTree(Main.parse(source, "Example.java")), "Example.m");

        JsonNode cond = g.node("привет.isEmpty()");
        int start = cond.at("/source_range/start").asInt();
        assertEquals(source.indexOf("привет.isEmpty()"), start);
        assertEquals("привет.isEmpty()", source.substring(start, start + cond.at("/source_range/length").asInt()));
        assertEquals(4, cond.at("/source_range/line").asInt());
        JsonNode decl = g.node("String привет = \"мир 😀\";");
        assertEquals(source.indexOf("String привет"), decl.at("/source_range/start").asInt());
        g.edge(cond, g.node("привет = \"пусто\";"), "true");
    }

    @Test
    void emptyMethod() {
        Graph g = new Graph("");

        assertEquals(2, g.cfg.get("nodes").size());
        assertEquals(1, g.cfg.get("edges").size());
        g.edge(g.entry(), g.exit(), "next");
        assertEquals("m0.n0", g.cfg.get("entry").asText());
        assertEquals("m0.n1", g.cfg.get("exit").asText());
    }

    @Test
    void abstractMethodsHaveNoCfgAndOtherMethodsGetOwnIds() {
        JsonNode root = JSON.valueToTree(Main.parse("""
                abstract class Example {
                    abstract void a();
                    Example() { }
                    void b() { }
                    void c() { class Local { void d() { } } }
                }
                """, "Example.java"));

        JsonNode members = root.at("/types/0/members");
        assertTrue(members.get(0).get("cfg").isNull());
        Set<String> prefixes = new HashSet<>();
        for (int i = 1; i < 4; i++) {
            prefixes.add(members.get(i).at("/cfg/entry").asText().split("\\.")[0]);
        }
        assertEquals(3, prefixes.size(), "node ids must not collide between methods");
        assertEquals("constructor", members.get(1).get("kind").asText());
    }

    @Test
    void malformedSourceDoesNotBreakCfg() {
        JsonNode root = JSON.valueToTree(Main.parse("""
                class Example {
                    void ok(int a) { if (a > 0) { a--; } }
                    void broken( {
                        while (
                    }
                }
                """, "Example.java"));

        assertEquals("partial", root.get("status").asText());
        Graph g = new Graph(root, "Example.ok");
        g.edge("a > 0", "a--;", "true");
        for (JsonNode m : root.at("/types/0/members")) {
            JsonNode cfg = m.get("cfg");
            assertTrue(cfg == null || cfg.isNull() || cfg.has("entry"));
        }
    }
}
