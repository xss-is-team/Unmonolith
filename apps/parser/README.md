# Java Blueprints parser

Parses one Java file with Eclipse JDT Core and prints a nested JSON AST with resolved types,
resolved method calls, compiler diagnostics and a per-method control-flow graph. No complexity
analysis or transformations yet (Phases 4–5).
The submitted program is never executed.

## Build and run

Requires JDK 21 or newer and Maven. From the repository root, `./dev parser-build` creates the executable JAR; the optional root Nix flake also provides these tools.
The code is compiled with `--release 21`.

```sh
./dev parser-build                       # runs Maven tests and builds the shaded JAR
java -jar apps/parser/target/ASTPARSER-1.0-SNAPSHOT.jar Example.java
```

## CLI contract

`java -jar parser.jar <file.java>` — exactly one positional argument, UTF-8 source.

| Channel | Content |
|---|---|
| stdout | exactly one JSON document (pretty-printed UTF-8) |
| stderr | human-readable errors and stack traces; empty on success |

| Exit | Meaning | stdout |
|---|---|---|
| 0 | parsed; syntax errors, if any, are in `diagnostics` | AST JSON |
| 1 | input unreadable or internal parser failure | JSON with `status: "failed"` |
| 2 | usage error (wrong argument count) | empty |

Malformed Java is not a failure: JDT recovers, so the AST is partial, `status` is `"partial"`
and `diagnostics` lists the syntax errors.

## JSON output

```text
protocol_version  1
status            "completed" | "partial" (any error diagnostic) | "failed" (no AST)
semantic          {bindings: true|false} — false only if binding resolution itself threw
package           string | null
imports[]         {name, static, on_demand}
types[]           class | interface | enum | record | annotation_type
methods[]         flat list {id, name, source_range} of every method/constructor
diagnostics[]     {code, category: syntax|semantic|analysis, severity: error|warning|info, message, source_range}
```

Every node carries `kind` and `source_range {start, length, line, end_line}` (`start`/`length` are
character offsets into the source, lines are 1-based). Expressions are exact source text.

- Types: `name`, `modifiers`, `extends`, `implements`, `members[]`; enums add `constants[]`,
  records add `components[]`.
- Members: `method`/`constructor` (`id`, `name`, `modifiers`, `return_type`, `parameters[]`,
  `throws[]`, `body` — statement list, `null` for abstract), `field`, `initializer`, nested types.
  Method `id` is `Outer.Inner.name(paramType,...)`.
- Statements nest by containment; bodies are always statement lists (a block is flattened):

| kind | fields |
|---|---|
| `if` | `condition`, `then[]`, `else[]` or `null` (`else if` = nested `if`) |
| `for` | `init[]`, `condition` or `null`, `update[]`, `body[]` |
| `foreach` | `variable`, `iterable`, `body[]` |
| `while` / `do_while` | `condition`, `body[]` |
| `switch` | `selector`, `cases[]` of `{kind:"case", default, labels[], arrow, body[]}` |
| `try` | `resources[]`, `body[]`, `catches[]` of `{exception_type, name, body[]}`, `finally[]` or `null` |
| `return`, `throw`, `yield` | `value` |
| `break`, `continue` | `label` or `null` |
| `expression` | `expression`, `expression_type` (JDT node class) |
| `variable_declaration` | `modifiers`, `var_type`, `fragments[]` `{name, extra_dimensions, initializer}` |
| `block`, `labeled`, `synchronized`, `assert`, `local_type`, `this_call`, `super_call`, `empty` | see `AstJson.java` |
| `unknown` | `node_type`, `text` — emitted instead of silently dropping unsupported nodes |

Switch contents are grouped per `case`; old-style fallthrough shows up as a case body without `break`.
Lambdas, anonymous classes and switch expressions inside expressions are kept as text only.

## Semantic analysis

The file is parsed with JDT bindings enabled (`resolveBindings`, bindings recovery, statements
recovery). All semantic fields are additive; the syntax-only fields above are unchanged.

- **Environment.** Single file only. Types resolve against the JDK that runs the parser
  (`java.lang`, `java.util`, `java.io`, …). There is no project classpath, no Maven/Gradle
  import, and no other source files: anything outside the JDK is reported as unresolved.
  Language compliance is fixed to Java 21; a JDK 21 runtime resolves JDK 21 classes.
- **Types** (`resolved_type`, `resolved_return_type`, `resolved_throws[]`,
  `resolved_variable_type`, `resolved_exception_types[]`, `receiver_type`) are
  `{name, kind}`. `name` is fully qualified with type arguments (`java.util.List<java.lang.String>`,
  `int[]`); `kind` is `primitive | class | interface | enum | record | annotation | array |
  type_variable | null | unresolved`. Wrappers are ordinary classes (`java.lang.Integer`).
  Where they appear: type declarations (`qualified_name`), method return/throws, parameters,
  variable fragments (locals and fields), `foreach` variable, `catch` clauses, `throw` (`resolved_type`).
- **Calls.** Any statement that contains expressions gets `calls[]` (omitted when empty): every
  method call and `new` expression in that statement's own expressions, outermost first. Entry:
  `kind` (`method_call | constructor_call`), `name`, `expression`, `source_range`, `resolved`, and when resolved
  `declaring_class` (erased), `signature` (`length()`, `put(java.lang.String, java.lang.Integer)`),
  `parameter_types[]`, `return_type` (null for constructors), `static`, `thrown_exceptions[]`
  (declared checked exceptions of the callee). Method calls on a receiver also carry `receiver_type`.
  Calls inside lambda bodies and anonymous class bodies are not collected.
- **Diagnostics** come from the JDT compiler: type mismatches, unresolved names/methods/types,
  unhandled checked exceptions, unreachable catch blocks, plus JDT's default warnings
  (unused variables, resource leaks, …). `category` is `syntax` or `semantic`.
  Any error makes `status` `"partial"`; warnings alone keep `"completed"`.
- JDT skips flow analysis (e.g. "unhandled exception") in a method that already has resolution
  errors, so fixing one error can reveal another.
- Unresolved bindings never fail the run: the field is `null`, `resolved: false`, or `kind: "unresolved"`.
- The API preserves a safe uploaded basename, so JDT reports a public type whose name does not
  match its source filename.
- `source_range.start`/`length` are UTF-16 code units (Java `String` indices), not bytes and not
  Unicode code points; an emoji counts as 2. The Rust side must convert if it slices UTF-8 bytes.
  JDT diagnostics use the same unit.

## Control-flow graph

Every `method` and `constructor` member with a body has `"cfg"` (`null` for abstract/interface
methods and when construction failed; failure adds a `cfg_build_failed` warning diagnostic with
`category: "analysis"` and never touches the AST or semantic fields). `protocol_version` stays 1.
Initializer blocks and lambda bodies get no graph; methods of nested and local classes get their own.

```json
"cfg": {
  "method_id": "Example.f(int)",
  "entry": "m0.n0", "exit": "m0.n1",
  "nodes": [
    {"id": "m0.n2", "kind": "branch", "statement_kind": "while", "text": "i < n",
     "source_range": {"start": 61, "length": 5, "line": 4, "end_line": 4}},
    {"id": "m0.n9", "kind": "statement", "statement_kind": "expression", "text": "dead();",
     "source_range": {...}, "reachable": false}
  ],
  "edges": [
    {"from": "m0.n2", "to": "m0.n3", "kind": "true"},
    {"from": "m0.n5", "to": "m0.n2", "kind": "next", "back": true},
    {"from": "m0.n4", "to": "m0.n6", "kind": "case", "label": "1, 2"}
  ]
}
```

- **Node ids** are `m<k>.n<i>`: `k` numbers the graphs of this response in source order, `i` is
  the position in `nodes` (entry is always `n0`, exit `n1`, then breadth-first, unreachable last).
  They are only meaningful within one analysis result (one revision); they change when the source changes.
- **Node `kind`**: `entry`, `exit`, `statement` (simple statement), `branch` (condition of
  `if`/`while`/`for`/`do_while`/`foreach`), `switch`, `return`, `throw`, `break`, `continue`,
  `catch`, `rethrow` (end of an exceptional `finally`), `join` (condition-less `for(;;)` head).
  `statement_kind` uses the AST `kind` names (`expression`, `variable_declaration`, `if`, `foreach`,
  `for_init`, `for_update`, `resource`, `synchronized`, `local_type`, …). `text` and `source_range`
  are the node's own source (the condition for a branch, the whole statement otherwise); entry
  and exit carry the method's range. Absent fields are omitted, not `null`.
- **Edge `kind`**: `next`; `true`/`false` (branch and loop conditions); `case` (with `label`: the
  case constants) and `default` (switch dispatch); `break`, `continue`, `return` (jump nodes);
  `exception` (with `label`: the catch type or `finally`). `back: true` marks an edge that closes
  a cycle in a depth-first walk from the entry (loop iteration end, `continue` into a header, `do`
  condition → body). It is omitted when false.
- **Reachability.** Nodes unreachable from the entry (code after `return`, a `finally` clone that
  can never run, a catch nothing can reach) are kept and marked `"reachable": false`.

Modeled: sequential flow, `if`/`else if`, `while`, `do/while`, `for` (init → condition → body →
update → condition; `continue` goes to the update), enhanced `for`, labeled `break`/`continue`,
`switch` statements (fallthrough for `case X:`, no fallthrough for `case X ->`, an implicit
`default` edge when there is no default case), `return`, `throw`, `try`/`catch`/`finally`,
try-with-resources, `synchronized`.

`finally` is **cloned** per exit path (normal completion, each `return`/`break`/`continue` that
crosses it, and exceptional exit followed by a `rethrow` node), so clones share `text` and
`source_range` but have distinct ids; paths never merge through a shared `finally`.

Limitations (the graph is a conservative over-approximation, not a proof):
- **Exceptions** are modeled only inside a `try`. Nodes that may throw (calls, `new`, array
  access/creation, casts, `/` and `%`, `throw`) get `exception` edges to every catch clause that
  could match and, unless some clause catches `Exception`/`Throwable`, onward to the outer handler or
  `exit`. For `throw` the thrown/catch types are compared with JDT bindings when resolvable;
  for calls every clause is assumed possible. Implicit exceptions outside any `try`, `Error`s, and
  the implicit `close()` of try-with-resources are not modeled.
- Conditions are single nodes: `&&`, `||`, `?:` short-circuit paths are not split.
- Switch expressions, lambdas and anonymous classes are opaque (expression text only).
  Pattern/`case null` labels are emitted as text; exhaustiveness is not analyzed, so a pattern
  switch without `default` still gets a `default` edge past the switch.
- Empty statements produce no node. Implicit `super()` calls are not shown.
- Constant conditions are not evaluated (`while (true)` still has a `false` edge), and
  definite-assignment/unreachable-code rules of the compiler are not applied; JDT reports those
  as diagnostics.

## Mismatch with `../api` (not changed here)
## API integration

The Rust API invokes this positional CLI, captures the JSON from stdout, attaches its trusted
project revision, and preserves the complete parser document as the per-revision analysis artifact.

## Transform mode

```sh
java -jar parser.jar transform --input Main.java --operation operation.json --output Transformed.java
```

All three options are required. The analysis command (`parser.jar <file.java>`) is unchanged.
The uploaded code is never compiled or executed.

`operation.json` (exact shape, unknown or missing fields are rejected):

```json
{"method_id": "Example.process(int)", "node_id": "m0.n3",
 "operation": {"type": "replace_condition", "expression": "count >= 0"}}
```

`replace_condition` replaces the condition of an `if`, `while`, `do/while` or traditional `for`
(a CFG node with `kind: "branch"` and `statement_kind` of `if`, `while`, `do_while` or `for`).
The source is reparsed, the CFG rebuilt, and the node is resolved to its JDT expression by exact
source range; the edit is made with `ASTRewrite`, so everything else (formatting, comments, line
endings) is byte-for-byte unchanged. CFG ids are revision-local: take them from an analysis of
exactly the source being transformed.

| Exit | stdout |
|---|---|
| 0 | `{status:"completed", operation, method_id, node_id, output_file, diagnostics[]}` (`diagnostics` = new warnings only) |
| 1 | `{status:"failed", error:{code, message}}` |
| 2 | same failed shape with `invalid_arguments` (bad command line) |

Error codes: `unknown_method`, `unknown_node`, `unsupported_node` (not an if/while/do-while/for
condition, e.g. foreach, switch, condition-less `for`), `invalid_expression` (not exactly one
syntactically valid expression), `validation_failed` (reparse shows a new semantic error, e.g.
non-boolean condition or unknown name), plus `invalid_operation`, `invalid_arguments`,
`input_unreadable`, `output_unwritable`, `internal_error`.

The output file is written (atomically) only on success and is never the input file. No new CFG is
returned; request a fresh analysis of the stored output.
