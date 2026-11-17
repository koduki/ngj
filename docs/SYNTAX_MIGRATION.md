# v0.3 syntax migration and acceptance

## Scope and implementation status

v0.3 consolidates the language into the canonical forms in [EBNF.md](EBNF.md). The Java compiler, tests and fixtures implement the v0.3 canonical syntax.

| v0.2 form | v0.3 form |
|---|---|
| module / pub / data / let | package / public / record / val |
| let mut | var |
| ! {} / ! {DbRead} | effects() / effects(DbRead) |
| data declaration with braces or draft positional fields | record Name(field: Type); |
| Name { field: value } | Name(value) |
| enum constant / E::Variant(...) | E.Variant(...) with parentheses even when empty |
| implicit function tail return / return without `;` | mandatory `return <expr>;` in non-`Unit` functions (`NGJ-RETURN-001`); tail expression in `if`/`match`/`for-yield` blocks |
| for x in xs | for (x <- xs) { ... }; |
| implicit or omitted statement separator | mandatory semicolon after statements (`val`, `var`, assignment, `return`, expression statement) |
| grouped imports | one qualified symbol import per declaration |
| Rust-style lambda bars | (x: T) => { ... } |
| expression-position f<T>(x) | f::<T>(x) |
| match value with mixed arm forms | match (value) { case Pattern => { ... } } |

The mapping is semantic where return/propagation/callback boundaries change.

## Small core and later profiles

1. Core migration covers declarations, immutable `val`, `record`/`enum` construction, typed functions with mandatory `return`, arithmetic, `if`/`match`, expression-block tail expressions, `Result`/`Option`, holes and JSON diagnostics.
2. Sequential extension adds `var`/assignment, standard eager `List`, typed lambdas and `for`/`while`.
3. Resource/concurrency extension adds lexical borrowing, `build`, `scope`/`fork` and cleanup.
4. Richer collection protocols, effects polymorphism, contracts and information-flow rules follow their own acceptance tests.

An unavailable feature produces an unsupported-feature error diagnostic (`NGJ-UNSUPPORTED-001`, `NGJ-EFFECT-001`, `NGJ-PARSE-001`, etc.). All function signatures specify `effects`, including private functions. The initial core accepts `effects()` only.

## Control-flow boundaries

| Context containing `?` or `return` | Target |
|---|---|
| `fn` body, nested ordinary blocks, `if`/`match`/`while` | That `fn` |
| `build`/`scope` in `fn` | That `fn`, after cleanup |
| Explicit lambda body | That lambda; its result must be `Result` with matching `E` for `?` |
| `fork` body | Child-task function; parent receives the child `Result` through `join` |
| `for`/`for-yield` body, including nested ordinary blocks/`while` | Rejected (`NGJ-PROP-002`) until a separately specified typed traversal exists |
| Explicit lambda nested inside a `for` body | That lambda |

For body callbacks capture read-only `val` values under ownership checks. Outer `var` capture is rejected in the initial sequential profile (`NGJ-CAPTURE-001`). For bodies may create local `var`. `while` in a function can update that function's `var`.

A `for-yield` body that calls a `Result`-returning function without `?` returns `List<Result<T,E>>`. It does not automatically produce `Result<List<T>,E>`.

## Concrete examples

Accepted by the v0.3 parser and type checker:

```scala
package example.pricing;

public record Amount(value: Long);

public enum PricingError {
    Negative(),
    TooLarge()
}

public fn price(amount: Long) -> Result<Amount, PricingError> effects() {
    val valid: Result<Long, PricingError> = if (amount < 0L) {
        Err(PricingError.Negative())
    } else {
        Ok(amount)
    };
    val checked = valid?;
    return Ok(Amount(checked));
}
```

The expected error type flows from propagation or return type to the initializer where required. If the checker cannot infer it locally, require `val valid: Result<Long, PricingError> = ...`; it must never invent `Any` or alter the error type.

Sequential extension examples, assuming declared `List` bindings:

```scala
val doubled = for (x <- numbers) yield { x * 2 };
for (x <- numbers) { consume(x); };
var index = 0;
while (index < limit) { index = index + 1; };
```

## Required migration tests

| Case | Expected result |
|---|---|
| `val n = 1; return n;` at end of `Int` function | valid |
| `Int` function with bare tail `42` (missing `return`) | invalid, `NGJ-RETURN-001` diagnostic |
| `Int` function ending in `1;` | invalid, lost-result diagnostic (`NGJ-TYPE-017` / `NGJ-RETURN-001`) |
| `return 42` without semicolon | missing-semicolon diagnostic (`NGJ-PARSE-008`) |
| early return in guard clause (`if (x < 0) { return 0; } return x;`) | valid |
| adjacent `val`/`var` bindings separated only by newline | missing-semicolon diagnostic (`NGJ-PARSE-007`) |
| `if` with mismatched branch types | type error (`NGJ-TYPE-011`) |
| `if` without `else` and non-`Unit` body | type error (`NGJ-TYPE-021`) |
| incomplete `enum` `match` | exhaustiveness error (`NGJ-MATCH-001`) |
| `None()` and `case None()` | valid `Option` construction/pattern |
| bare `None`/`Some`/`Ok`/`Err` in expression | rejected (`NGJ-TYPE-022`), must use parentheses e.g. `None()` |
| bare `x` pattern | binding, never guessed to be a constant |
| `f()?.field` | same AST meaning as `(f()?).field` |
| `a + f()?` | `a + (f()?)` |
| `?body` | incomplete, hole position/type preserved (`NGJ-HOLE-001`) |
| postfixed `?` on `Option` | type error (`NGJ-TYPE-020`) |
| malformed/unclosed angle arguments | bounded diagnostic recovery |
| `f::<T>(x)` | syntactically recognized, rejected in MVP (`NGJ-UNSUPPORTED-001`) |
| `List` generator, with/without `yield` | `List<U>`/`Unit` respectively |
| `for` over non-`List` expression | type error (`NGJ-TYPE-026`) |
| `for` body containing `?` or `return` | propagation-boundary diagnostic (`NGJ-PROP-002`) |
| callback capturing outer `var` | capture diagnostic (`NGJ-CAPTURE-001`) |
| `while` updating local `var` | valid in sequential profile |
| obsolete `module`/`pub`/`data`/`let`/`! {}` spellings | migration diagnostic (`NGJ-MIGRATE-*`) |
| wildcard import (`import pkg.*;`) | unsupported syntax (`NGJ-PARSE-001`) |
| grouped import (`import pkg.{A, B};`) | migration diagnostic (`NGJ-MIGRATE-002`) |
| named/default args | unsupported syntax (`NGJ-PARSE-007`) |

## Work items

- [x] Update Lexer/TokenType and Parser, preserving UTF-8 source spans.
- [x] Consolidate AST construction/calls and implement deterministic block and `return` parsing.
- [x] Enforce mandatory `return` on non-`Unit` functions (`NGJ-RETURN-001`) and typed `Result` propagation.
- [x] Align constructors, pattern matching, `Unit` and expected-type diagnostics.
- [x] Migrate actual source fixtures and inline test programs.
- [x] Update CLI context signatures, examples and coverage after conformance runs.
- [x] Add sequential `List`/loop profile with explicit callback restrictions.
