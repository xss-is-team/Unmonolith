# Java Blueprints parser

Parses one Java file with Eclipse JDT Core and prints a nested JSON AST, a statement-level CFG,
resolved types and calls, and compiler diagnostics. Complexity analysis is not implemented yet.
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
| stderr | progress lines (`progress: <stage>`) and human-readable errors |

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
diagnostics[]     {code, category: syntax|semantic, severity: error|warning|info, message, source_range}
cfgs[]            {method_id, nodes[], edges[]} for methods with bodies
```

Each CFG node has `id`, `kind`, `label`, and `source_range`; each edge has `source`, `target`,
and `kind` (`true`, `false`, `back`, `break`, `continue`, `exception`, `finally`, `case: …`, etc.).
The graph is statement-level: control statements are explicit, and unsupported statement forms remain
visible as opaque nodes. Exception edges represent resolved declared exceptions caught by surrounding
handlers; unresolved calls connect conservatively to handlers and method exit. Unchecked exceptions are
not exhaustively modeled, so this is an analysis view, not a proof of every runtime path.
AST remains the source of truth for source text and future transformations; CFG alone cannot regenerate Java.

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

## API integration

The Rust API invokes this positional CLI, captures the JSON from stdout, attaches its trusted
project revision, and preserves the complete parser document as the per-revision analysis artifact.
