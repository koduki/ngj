# NGJ MVP Coverage Matrix

> Issue #2 tracking document
> Status: v0.3 implementation tracking
> The v0.3 syntax uses `package`/`public`/`record`/`val`/`var`/`return`, `effects()`, mandatory `return` in functions, expression-block tail expressions, and mandatory semicolons. See [migration requirements](SYNTAX_MIGRATION.md).

This document tracks which language and tooling features are included in the MVP,
their implementation status, and explicit exclusions.

## Language Subset Coverage

### Module System

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `package` declaration | Yes | ✅ | Single module per file, v0.3 canonical syntax |
| Explicit `import` | Yes | ✅ | Named imports only |
| Wildcard import `*` | **No - Reject** | ✅ | Produces `NGJ-PARSE-001` |
| `public` visibility | Yes | ✅ | Public vs module-private |
| Multi-file name resolution | Yes | ✅ | Cross-module references via `NameResolver.resolveAll` |
| Legacy `module`/`pub` | **No - Reject** | ✅ | Migration diagnostic `NGJ-MIGRATE-001`/`003` |

### Basic Types

| Type | MVP Required | Status | Notes |
|------|-------------|--------|-------|
| `Int` (i32) | Yes | ✅ | Overflow = fault |
| `Long` (i64) | Yes | ✅ | Overflow = fault |
| `Bool` | Yes | ✅ | `true`/`false` |
| `String` | Yes | ✅ | Immutable UTF-8 |
| `Unit` | Yes | ✅ | Implicit for side-effect statements |

### Value Types

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `record` (immutable struct) | Yes | ✅ | v0.3 syntax: `record Name(field: Type);` |
| `enum` (closed sum type) | Yes | ✅ | Exhaustive match required (`NGJ-MATCH-001`) |
| Constructor call | Yes | ✅ | v0.3 syntax: `TypeName(value)` |
| Field access | Yes | ✅ | `value.field` |
| Legacy `data` keyword | **No - Reject** | ✅ | Migration diagnostic `NGJ-MIGRATE-005` |

### Functions and Expressions

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Typed function declaration | Yes | ✅ | Explicit param/return types |
| Mandatory `return` in functions | Yes | ✅ | `return <expr>;` required on all paths (`NGJ-RETURN-001`) |
| `val` binding | Yes | ✅ | Immutable local variables |
| `var` binding | Yes | ✅ | Mutable local variables |
| Assignment statement | Yes | ✅ | `var = value;` |
| Function call | Yes | ✅ | Named function invocation |
| `if`/`else` expression | Yes | ✅ | Expression form with parenthesized condition |
| Expression block tail | Yes | ✅ | Last expr in `if`/`match`/`for-yield` block is value |
| Integer arithmetic | Yes | ✅ | `+` `-` `*` `/` `%` |
| Comparison operators | Yes | ✅ | `==` `!=` `<` `<=` `>` `>=` |
| Short-circuit logic | Yes | ✅ | `&&` `||` |
| Exhaustive `match` | Yes | ✅ | v0.3 syntax: `match (value) { case Pattern => { ... } }` |
| Mandatory semicolons | Yes | ✅ | Required after statements; not after expression-block tail |
| Legacy `let` keyword | **No - Reject** | ✅ | Migration diagnostic `NGJ-MIGRATE-006` |

### Error Handling

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `Result<T, E>` | Yes | ✅ | Built-in / standard library enum |
| `Option<T>` | Yes | ✅ | Built-in / standard library enum |
| `Ok(v)` / `Err(e)` | Yes | ✅ | Result constructors |
| `Some(v)` / `None()` | Yes | ✅ | Option constructors, `None()` with parentheses |
| Postfix `?` on Result | Yes | ✅ | Same-E propagation only |
| Postfix `?` on Option | **No - Reject** | ✅ | v0.3 rejects, use `match` instead (`NGJ-TYPE-020`) |
| Different-E `?` | **No - Reject** | ✅ | Rejected (`NGJ-TYPE-019`), must use explicit conversion |

### Type Inference

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Local `val`/`var` inference | Yes | ✅ | From initializer |
| Constructor expected type | Yes | ✅ | Type context flows down to holes and arguments |
| Public signature explicit | Yes | ✅ | No inference for public API |
| Unknown type as `Any` | **No - Reject** | ✅ | Must be error (`NGJ-NAME-001`) |

### Partial Programs

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Explicit `?name` hole | Yes | ✅ | Named placeholder |
| Hole expected type | Yes | ✅ | Local and return type context preserved |
| Hole local bindings | Yes | ✅ | Available names reported |
| Hole in release build | **Reject** | ✅ | Exit code 2 (incomplete) |

### Effects

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Pure `effects()` | Yes | ✅ | v0.3 syntax, no external effects |
| Named effects `effects(Effect1)` | **No - Reject** | ✅ | Unsupported feature error (`NGJ-EFFECT-001`) |
| Legacy `! {}` syntax | **No - Reject** | ✅ | Migration diagnostic `NGJ-MIGRATE-004` |

### Collections

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `List<T>` | Yes | ✅ | Eager list backed by `java.util.List` |
| `Nil()` | Yes | ✅ | Empty list constructor |
| `listOf(...)` | Yes | ✅ | List literal constructor |

### Loops and Iteration

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `for (x <- list) { ... }` | Yes | ✅ | Iteration over `List<T>`, single generator |
| `for (x <- list) yield { ... }` | Yes | ✅ | List comprehension producing `List<U>` |
| `while (condition) { ... }` | Yes | ✅ | Loop with parenthesized condition |
| `?` or `return` in `for` body | **No - Reject** | ✅ | Propagation boundary diagnostic `NGJ-PROP-002` |
| Outer `var` capture in `for` | **No - Reject** | ✅ | Capture diagnostic `NGJ-CAPTURE-001` |
| `for-yield` over non-`?` `Result` | Yes | ✅ | Produces `List<Result<T,E>>`, not `Result<List<T>,E>` |

### Execution

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| JVM class output | Yes | ✅ | Class-File API (standard since Java 24) |
| `final class` for record | Yes | ✅ | JVM representation with structural `equals`/`hashCode` |
| Sealed hierarchy for enum | Yes | ✅ | Interface + final variant classes |
| Integer overflow fault | Yes | ✅ | Runtime check via `Math.xxxExact` |
| Short-circuit evaluation | Yes | ✅ | Left-to-right |

## CLI Coverage

| Command | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `ngjc check` | Yes | ✅ | Validate source |
| `ngjc context` | Yes | ✅ | Hole context retrieval |
| `ngjc inspect` | Yes | ✅ | Symbol information |
| `ngjc build` | Yes | ✅ | Generate class files |
| `--format=json` | Yes | ✅ | Machine-readable output |
| `--revision=<hash>` | Yes | ✅ | Source digest validation |

### Exit Codes

| Code | Meaning | Status |
|------|---------|--------|
| 0 | Success / valid | ✅ |
| 1 | Invalid (errors) | ✅ |
| 2 | Incomplete (holes) | ✅ |
| 3 | Revision mismatch / bad input | ✅ |
| 4 | Internal compiler error | ✅ |

## Explicitly NOT in MVP

The following features are explicitly excluded and must be rejected with
`unsupported_feature` diagnostic:

### Ownership & Resources
- `&T` / `&mut T` borrowing
- `resource` declarations
- `actor` declarations  
- `build` / freeze blocks
- `Bytes` / `Slice` / `Arena`
- Lifetime annotations

### Effects & Capabilities
- Effect declarations other than `effects()`
- Capability types
- `Declassify` / information flow

### Concurrency
- `scope` / `fork` / `join`
- `Channel`
- `Task` handles
- Cancellation

### Contracts & Verification
- `requires` / `ensures` clauses
- Property testing
- SMT proof obligations

### Advanced Types
- User-defined generics (beyond Result/Option)
- Higher-order functions
- Closures / lambdas (typed lambdas in sequential profile)
- Typestate

### Java Interop
- Direct Java method calls
- `unsafe` blocks
- Adapter declarations

### Other (Pre-Valhalla Migration)
- ~~Valhalla backend~~ → **Valhalla is now the foundation, not optional**
- Native Image backend (requires Valhalla compatibility)
- LSP server
- MCP server
- Incremental compilation
- `check-patch` command

## Valhalla Foundation Migration Notes

> **Important:** The language specification v0.3 defines Valhalla value classes as the foundation. The compiler enforces value semantics (immutability, structural `==` via `Objects.equals`, no `null`, sealed enum hierarchies) and supports `--target=valhalla` (default) as well as `--target=valhalla-compat` / `java25`.

### Compiler Migration Checklist

- [ ] Update Gradle/build to use JDK 28 EA toolchain when available
- [ ] Add `--enable-preview` to compile and runtime flags for `ACC_VALUE` classfile bits
- [x] Implement structural `equals`/`hashCode` for `record` and `enum` variants
- [x] Implement sealed hierarchy for enum variants
- [x] Enforce no-`null` rule (`NGJ-TYPE-020`)
- [x] Implement value-based `==` comparison semantics (`Objects.equals` for composite value types)
- [x] Enhance match exhaustiveness diagnostics (include missing variant names in `NGJ-MATCH-001`)
- [x] Verify `Int`/`Long`/`Bool` behave as value types
- [ ] Add `identity class` syntax support (staged feature)
- [ ] Add Java interop tests for null → Option conversion (staged feature)

### Enum Algebraic Data Type Support

The v0.3 spec defines enums as sealed algebraic data types:

```scala
// Supported in v0.3
public enum Shape {
    Circle(radius: Int),
    Rectangle(width: Int, height: Int),
    Point()
}

// Result and Option are ordinary enums
public enum Option<T> {
    Some(value: T),
    None()
}
```

Required compiler changes:
- [x] Parse payload-carrying enum variants
- [x] Generate sealed hierarchy (interface + variant classes)
- [x] Support nested/destructuring patterns in `match`
- [x] Implement exhaustiveness checking with precise diagnostics (`NGJ-MATCH-001`)
- [x] Support built-in generic enums (`Option<T>`, `Result<T, E>`)
- [x] Ensure `?` operator works with `Result<T, E>`

### Risk: JDK 28 Preview Changes

JEP 401 is a preview feature. If semantics change before final:
- Compiler output may need adjustment
- Test suite must be re-validated
- Source compatibility is a spec goal; binary compatibility is not guaranteed during preview

### Fallback Strategy

If Valhalla features are unavailable or unstable:
- `valhalla-compat` profile: emit identity classes with value semantics enforced at NGJ level
- Language semantics remain identical
- JVM optimizations (flattening, scalarization) are lost
- This is a temporary fallback, not a permanent alternative

## Validation Requirements

Each feature must have:

1. **Positive tests**: Valid code that exercises the feature
2. **Negative tests**: Invalid code that must be rejected with specific diagnostic ID
3. **Execution tests** (where applicable): Runtime behavior verification

Test fixtures are located in:
- `compiler/src/test/resources/fixtures/positive/` - Valid NGJ source files
- `compiler/src/test/resources/fixtures/negative/` - Invalid NGJ source files (with expected diagnostics)

## Status Legend

- 🔲 Not started
- 🔶 In progress
- ✅ Complete
- ❌ Blocked
