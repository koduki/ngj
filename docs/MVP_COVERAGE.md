# NGJ MVP Coverage Matrix

> Issue #2 tracking document
> Status: Implementation in progress

This document tracks which language and tooling features are included in the MVP,
their implementation status, and explicit exclusions.

## Language Subset Coverage

### Module System

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `module` declaration | Yes | 🔲 | Single module per file |
| Explicit `import` | Yes | 🔲 | Named imports only |
| Wildcard import `*` | **No - Reject** | 🔲 | Must produce error |
| `pub` visibility | Yes | 🔲 | Public vs module-private |
| Multi-file name resolution | Yes | 🔲 | Cross-module references |

### Basic Types

| Type | MVP Required | Status | Notes |
|------|-------------|--------|-------|
| `Int` (i32) | Yes | 🔲 | Overflow = fault |
| `Long` (i64) | Yes | 🔲 | Overflow = fault |
| `Bool` | Yes | 🔲 | `true`/`false` |
| `String` | Yes | 🔲 | Immutable UTF-8 |
| `Unit` | Yes | 🔲 | `()` |

### Value Types

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `data` (immutable struct) | Yes | 🔲 | Structural equality |
| `enum` (closed sum type) | Yes | 🔲 | Exhaustive match required |
| Constructor call | Yes | 🔲 | `TypeName { field: value }` |
| Field access | Yes | 🔲 | `value.field` |

### Functions and Expressions

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Typed function declaration | Yes | 🔲 | Explicit param/return types |
| `let` binding | Yes | 🔲 | Immutable local variables |
| Function call | Yes | 🔲 | Named function invocation |
| `if`/`else` expression | Yes | 🔲 | Expression form |
| Block expression | Yes | 🔲 | Last expr is value |
| `return` statement | Yes | 🔲 | Early return |
| Integer arithmetic | Yes | 🔲 | `+` `-` `*` `/` `%` |
| Comparison operators | Yes | 🔲 | `==` `!=` `<` `<=` `>` `>=` |
| Short-circuit logic | Yes | 🔲 | `&&` `||` |
| Exhaustive `match` | Yes | 🔲 | All variants covered |

### Error Handling

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `Result<T, E>` | Yes | 🔲 | Built-in type |
| `Option<T>` | Yes | 🔲 | Built-in type |
| `Ok(v)` / `Err(e)` | Yes | 🔲 | Constructors |
| `Some(v)` / `None` | Yes | 🔲 | Constructors |
| Postfix `?` | Yes | 🔲 | Same-E propagation only |
| Different-E `?` | **No - Reject** | 🔲 | Must use explicit match |

### Type Inference

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Local `let` inference | Yes | 🔲 | From initializer |
| Constructor expected type | Yes | 🔲 | Type context flows down |
| Public signature explicit | Yes | 🔲 | No inference for pub API |
| Unknown type as `Any` | **No - Reject** | 🔲 | Must be error |

### Partial Programs

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Explicit `?name` hole | Yes | 🔲 | Named placeholder |
| Hole expected type | Yes | 🔲 | Type context preserved |
| Hole local bindings | Yes | 🔲 | Available names reported |
| Hole in release build | **Reject** | 🔲 | Exit with error |

### Effects

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Pure `! {}` | Yes | 🔲 | No external effects |
| Any other effect | **No - Reject** | 🔲 | Unsupported feature error |

### Execution

| Feature | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| Java 25 class output | Yes | ✅ | Class-File API (standard since Java 24) |
| `final class` for data | Yes | ✅ | JVM representation |
| Sealed hierarchy for enum | Yes | 🔲 | JVM representation |
| Integer overflow fault | Yes | ✅ | Runtime check via Math.xxxExact |
| Short-circuit evaluation | Yes | ✅ | Left-to-right |

## CLI Coverage

| Command | MVP Required | Status | Notes |
|---------|-------------|--------|-------|
| `ngjc check` | Yes | 🔲 | Validate source |
| `ngjc context` | Yes | 🔲 | Hole context retrieval |
| `ngjc inspect` | Yes | 🔲 | Symbol information |
| `ngjc build` | Yes | 🔲 | Generate class files |
| `--format=json` | Yes | 🔲 | Machine-readable output |
| `--revision=<hash>` | Yes | 🔲 | Source digest validation |

### Exit Codes

| Code | Meaning | Status |
|------|---------|--------|
| 0 | Success / valid | 🔲 |
| 1 | Invalid (errors) | 🔲 |
| 2 | Incomplete (holes) | 🔲 |
| 3 | Revision mismatch / bad input | 🔲 |
| 4 | Internal compiler error | 🔲 |

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
- Effect declarations other than `! {}`
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
- Closures / lambdas
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

> **Important:** The current MVP compiler targets Java 25 baseline. The language specification v0.3 defines Valhalla value classes as the foundation. This section tracks what must change in the compiler to align with the spec.

### Current State (Java 25 Baseline)

The existing MVP compiler:
- Outputs `final class` for `data` types
- Uses reference semantics for all user types
- Does not enforce value type restrictions
- `==` behaves as reference comparison at JVM level

### Required Changes for Valhalla Foundation

| Component | Current | Target | Priority |
|-----------|---------|--------|----------|
| **JDK Version** | Java 25 | JDK 28 EA + `--enable-preview` | P0 |
| **data/record output** | `final class` | `value class` | P0 |
| **enum output** | sealed identity hierarchy | sealed value hierarchy | P0 |
| **Type checker** | No value/identity distinction | Enforce value type constraints | P0 |
| **`==` semantics** | Reference comparison | Value comparison for value types | P0 |
| **Match exhaustiveness** | Basic | Precise diagnostics with variant names | P0 |
| **CI pipeline** | Java 25 | JDK 28 EA with preview flags | P0 |
| **Test suite** | Reference semantics | Value semantics verification | P1 |

### Compiler Migration Checklist

- [ ] Update Gradle/build to use JDK 28 EA toolchain
- [ ] Add `--enable-preview` to compile and runtime flags
- [ ] Modify code generator to emit `value` modifier for data/record/enum
- [ ] Implement sealed value hierarchy for enum variants
- [ ] Update type checker to distinguish value vs identity types
- [ ] Add `identity class` syntax support
- [ ] Implement value-based `==` comparison semantics
- [ ] Enhance match exhaustiveness diagnostics (include missing variant names)
- [ ] Add negative tests for identity operations on value types
- [ ] Verify `Int`/`Long`/`Bool` behave as value types
- [ ] Add Java interop tests for null → Option conversion

### Enum Algebraic Data Type Support

The v0.3 spec defines enums as sealed algebraic data types:

```
// Must be supported
enum Shape {
    Circle { radius: Float },
    Rectangle { width: Float, height: Float },
    Point
}

// Result and Option are ordinary enums
enum Option<T> {
    Some { value: T },
    None
}
```

Required compiler changes:
- [ ] Parse payload-carrying enum variants
- [ ] Generate sealed value hierarchy (interface + value classes)
- [ ] Support nested/destructuring patterns in match
- [ ] Implement exhaustiveness checking with precise diagnostics
- [ ] Support generic enums
- [ ] Ensure `?` operator works with library-defined Result/Option

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
