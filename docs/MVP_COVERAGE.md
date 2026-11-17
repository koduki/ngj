# NGJ MVP Coverage Matrix

> Issue #2 tracking document
> Status: v0.3 implementation tracking
> The v0.3 syntax uses package/public/record/val/var, effects(), mandatory semicolons and tail expressions. See [migration requirements](SYNTAX_MIGRATION.md).

This document tracks which language and tooling features are included in the MVP,
their implementation status, and explicit exclusions.

## Language Subset Coverage

### Module System

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `package` declaration | Yes | ✅ | Single module per file, v0.3 canonical syntax |
| Explicit `import` | Yes | 🔲 | Named imports only |
| Wildcard import `*` | **No - Reject** | 🔲 | Must produce error |
| `public` visibility | Yes | ✅ | Public vs module-private |
| Multi-file name resolution | Yes | 🔲 | Cross-module references |
| Legacy `module`/`pub` | **No - Reject** | ✅ | Migration diagnostic NGJ-MIGRATE-001/003 |

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
| `enum` (closed sum type) | Yes | 🔲 | Exhaustive match required |
| Constructor call | Yes | ✅ | v0.3 syntax: `TypeName(value)` |
| Field access | Yes | ✅ | `value.field` |
| Legacy `data` keyword | **No - Reject** | ✅ | Migration diagnostic NGJ-MIGRATE-005 |

### Functions and Expressions

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Typed function declaration | Yes | ✅ | Explicit param/return types |
| `val` binding | Yes | ✅ | Immutable local variables |
| `var` binding | Yes | ✅ | Mutable local variables |
| Assignment statement | Yes | ✅ | `var = value;` |
| Function call | Yes | ✅ | Named function invocation |
| `if`/`else` expression | Yes | ✅ | Expression form with parenthesized condition |
| Block expression | Yes | ✅ | Last expr is value (tail expression) |
| Tail expression | Yes | ✅ | No `return` keyword; last expr is result |
| Integer arithmetic | Yes | ✅ | `+` `-` `*` `/` `%` |
| Comparison operators | Yes | ✅ | `==` `!=` `<` `<=` `>` `>=` |
| Short-circuit logic | Yes | ✅ | `&&` `||` |
| Exhaustive `match` | Yes | ✅ | v0.3 syntax: `match (value) { case Pattern => { ... } }` |
| Mandatory semicolons | Yes | ✅ | Required after statements; not after block tail |
| Legacy `let`/`return` | **No - Reject** | ✅ | Migration diagnostic NGJ-MIGRATE-002/004 |

### Error Handling

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `Result<T, E>` | Yes | ✅ | Built-in type |
| `Option<T>` | Yes | ✅ | Built-in type |
| `Ok(v)` / `Err(e)` | Yes | ✅ | Result constructors |
| `Some(v)` / `None()` | Yes | ✅ | Option constructors, `None()` with parentheses |
| Postfix `?` on Result | Yes | ✅ | Same-E propagation only |
| Postfix `?` on Option | **No - Reject** | ✅ | v0.3 rejects, use match instead (NGJ-TYPE-020) |
| Different-E `?` | **No - Reject** | 🔲 | Must use explicit match |

### Type Inference

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Local `val`/`var` inference | Yes | ✅ | From initializer |
| Constructor expected type | Yes | 🔲 | Type context flows down |
| Public signature explicit | Yes | ✅ | No inference for public API |
| Unknown type as `Any` | **No - Reject** | ✅ | Must be error |

### Partial Programs

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Explicit `?name` hole | Yes | ✅ | Named placeholder |
| Hole expected type | Yes | ✅ | Type context preserved |
| Hole local bindings | Yes | ✅ | Available names reported |
| Hole in release build | **Reject** | ✅ | Exit code 2 (incomplete) |

### Effects

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Pure `effects()` | Yes | ✅ | v0.3 syntax, no external effects |
| Named effects `effects(Effect1)` | **No - Reject** | 🔲 | Unsupported feature error |
| Legacy `! {}` syntax | **No - Reject** | ✅ | Migration diagnostic NGJ-MIGRATE-006 |

### Collections

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `List<T>` | Yes | ✅ | Eager list backed by java.util.List |
| `Nil()` | Yes | ✅ | Empty list constructor |
| `listOf(...)` | Yes | ✅ | List literal constructor |

### Loops and Iteration

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `for (x <- list) { ... }` | Yes | ✅ | Iteration over List<T>, single generator |
| `for (x <- list) yield { ... }` | Yes | ✅ | List comprehension producing List<U> |
| `while (condition) { ... }` | Yes | ✅ | Loop with parenthesized condition |
| `?` in for body | **No - Reject** | ✅ | Propagation boundary diagnostic NGJ-PROP-002 |
| Outer `var` capture in for | **No - Reject** | ✅ | Capture diagnostic NGJ-CAPTURE-001 |
| for-yield over non-? Result | Yes | ✅ | Produces List<Result<T,E>>, not Result<List<T>,E> |

### Execution

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| JDK 28 class output | Yes | ✅ | Class-File API, version 72 with preview minor version |
| `final class` for record | Yes | ✅ | JVM representation (value class flags pending JDK 28 validation) |
| Sealed hierarchy for enum | Yes | 🔲 | JVM representation (sealed abstract value class) |
| Integer overflow fault | Yes | ✅ | Runtime check via Math.xxxExact |
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

> **Important:** The MVP compiler builds on JDK 25 (toolchain) and targets JDK 28 EA + `--enable-preview` for runtime. The language specification v0.3 defines Valhalla value classes as the foundation. This section tracks what must change in the compiler to align with the spec.

### Current State (2026-11-17)

The existing MVP compiler:
- Builds with JDK 25 toolchain (java.lang.classfile API)
- Outputs class files with version 72 (JDK 28) and preview minor version
- Tests run on JDK 28 EA with `--enable-preview` in CI
- Outputs `final class` without ACC_IDENTITY (value class structure)
- Match expression codegen is incomplete (PR 3 will fix)

### Required Changes for Valhalla Foundation

| Component | Current | Target | Status |
|-----------|---------|--------|--------|
| **Build JDK** | JDK 25 toolchain | JDK 25 (class-file API) | ✅ |
| **Runtime JDK** | JDK 25 | JDK 28 EA + `--enable-preview` | ✅ CI configured |
| **Class file version** | 69 (JDK 25) | 72 (JDK 28) + preview | ✅ |
| **data/record output** | `final class` | `value class` (no ACC_IDENTITY) | ✅ Code present |
| **enum output** | sealed identity hierarchy | sealed abstract value class | 🔲 Needs PR 3 |
| **Type checker** | Basic | value/identity distinction | 🔲 Partial |
| **`==` semantics** | Generated equals() | Value comparison | ✅ Code present |
| **Match exhaustiveness** | Basic check | Precise variant names | ✅ Diagnostic present |
| **JDK 28 CI validation** | None | Full fixture tests | ✅ PR 1 |

### Compiler Migration Checklist

- [x] Update build.gradle.kts for JDK 28 class file version
- [x] Add `--enable-preview` to test flags
- [x] Configure CI to download and use JDK 28 EA
- [x] Add JDK 28 runtime test harness (JDK28RuntimeTest.java)
- [ ] Fix record constructor for JEP 401 strict fields (PR 3)
- [ ] Fix match expression codegen (PR 3)
- [ ] Fix Result/Option to use sealed value class hierarchy (PR 3)
- [ ] Add `identity class` syntax support (future)
- [ ] Add Java interop null → Option conversion (NULL-02, future)

### Enum Algebraic Data Type Support

The v0.3 spec defines enums as sealed algebraic data types:

```
// v0.3 syntax (must be supported)
enum Shape {
    Circle(radius: Float),
    Rectangle(width: Float, height: Float),
    Point()
}

// Result and Option are ordinary enums
enum Option<T> {
    Some(value: T),
    None()
}
```

Required compiler changes:
- [x] Parse payload-carrying enum variants
- [ ] Generate sealed abstract value class hierarchy (PR 3)
- [ ] Support nested/destructuring patterns in match (PR 3)
- [x] Implement exhaustiveness checking with diagnostics
- [ ] Full generic enum support (future profile)

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
