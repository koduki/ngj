# v0.3 syntax migration and acceptance

## Scope and implementation status

v0.3 consolidates the language into the canonical forms in [EBNF.md](EBNF.md). The current Java compiler, tests and fixtures implement v0.2 forms. This specification change requires a coordinated parser/checker/backend/fixture migration. Existing completion marks are not evidence of v0.3 support.

| v0.2 form | v0.3 form |
|---|---|
| module / pub / data / let | package / public / record / val |
| let mut | var |
| ! {} / ! {DbRead} | effects() / effects(DbRead) |
| data declaration with braces or draft positional fields | record Name(field: Type); |
| Name { field: value } | Name(value) |
| enum constant / E::Variant(...) | E.Variant(...) with parentheses even when empty |
| return expression | tail expression, possibly restructuring if/match |
| for x in xs | for (x <- xs) { ... }; |
| implicit or omitted statement separator | mandatory semicolon, except block tail |
| grouped imports | one qualified symbol import per declaration |
| Rust-style lambda bars | (x: T) => { ... } |
| expression-position f<T>(x) | f::<T>(x) |
| match value with mixed arm forms | match (value) { case Pattern => { ... } } |

The mapping is semantic where return/propagation/callback boundaries change. A global text replacement cannot preserve control flow.

## Small core and later profiles

1. Core migration covers declarations, immutable val, data/enum construction, typed functions, arithmetic, if/match, tail expressions, Result/Option, holes and JSON diagnostics.
2. Sequential extension adds var/assignment, standard eager List, typed lambdas and for/while. These are outside the original pure MVP's required collection features.
3. Resource/concurrency extension adds lexical borrowing, build, scope/fork and cleanup.
4. Richer collection protocols, effects polymorphism, contracts and information-flow rules follow their own acceptance tests.

An unavailable feature produces unsupported_feature. All function signatures specify effects, including private functions. The initial core accepts effects() only.

## Control-flow boundaries

| Context containing ? | Target |
|---|---|
| fn body, nested ordinary blocks, if/match/while | That fn |
| build/scope in fn | That fn, after cleanup |
| Explicit lambda body | That lambda; its result must be Result with matching E |
| fork body | Child-task function; parent receives the child Result through join |
| for/for-yield body, including nested ordinary blocks/while | Rejected until a separately specified typed traversal exists |
| Explicit lambda nested inside a for body | That lambda |

For body callbacks capture read-only val values under ownership checks. Outer var capture is rejected in the initial sequential profile. For bodies may create local var. While in a function can update that function's var.

A for-yield body that calls a Result-returning function without ? returns List<Result<T,E>>. It does not automatically produce Result<List<T>,E>.

## Concrete examples

A migration target, not yet accepted by the v0.2 parser:

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
    Ok(Amount(checked))
}
```

The expected error type must flow from propagation to the initializer where required. If the checker cannot infer it locally, require `val valid: Result<Long, PricingError> = ...`; it must never invent Any or alter the error type.

Sequential extension examples, assuming declared List bindings:

```scala
val doubled = for (x <- numbers) yield { x * 2 };
for (x <- numbers) { consume(x); };
var index = 0;
while (index < limit) { index = index + 1; };
```

## Required migration tests

| Case | Expected result |
|---|---|
| val n = 1; n at end of Int function | valid |
| Int function ending in 1; | invalid, lost-result diagnostic |
| adjacent bindings separated only by newline | missing-semicolon diagnostic |
| if with mismatched branch types | type error |
| if without else and non-Unit body | type error |
| incomplete enum match | exhaustiveness error |
| None() and case None() | valid Option construction/pattern |
| bare None in expression | rejected constructor-without-call |
| bare x pattern | binding, never guessed to be a constant |
| f()?.field | same AST meaning as (f()?).field |
| a + f()? | a + (f()?) |
| ?body | incomplete, hole position/type preserved |
| postfixed ? on Option | type error |
| malformed/unclosed angle arguments | bounded diagnostic recovery |
| f::<T>(x) | syntactically recognized, unsupported if profile lacks generic calls |
| List generator, with/without yield | List<U>/Unit respectively |
| for body containing ? | propagation-boundary diagnostic |
| lambda containing ? | stays within lambda, Result signature checked |
| callback capturing outer var | capture diagnostic |
| while updating local var | valid in sequential profile |
| fork containing ? | child result; parent function remains active |
| ? leaving scope/build | all required joins/cleanup occur |
| obsolete return/module/pub/data/let spellings | migration diagnostic |
| wildcard/grouped import, named/default args | unsupported syntax |

Test lexer tokens, parser AST shape, typed boundaries and runtime semantics separately. In particular compare the explicit for/while operations and lowered IR for evaluation order and number of body evaluations. Test empty List, first/middle failing Result values, and callbacks with side effects once those effects are enabled.

## Work items

- [ ] Update Lexer/TokenType and Parser, preserving UTF-8 source spans.
- [ ] Consolidate AST construction/calls and implement deterministic block parsing.
- [ ] Replace ReturnStmt lowering with tail paths and typed Result propagation.
- [ ] Align constructors, pattern matching, Unit and expected-type diagnostics.
- [ ] Migrate actual source fixtures and inline test programs as one implementation change.
- [ ] Update CLI context signatures, examples and coverage after conformance runs.
- [ ] Add sequential List/lambda/loop profile with explicit callback restrictions.
- [ ] Measure AI syntax/semantic success using the same tasks and budget.

No parser or runtime tests were executed for this documentation revision. Document checks and remote content verification do not establish parser conformance.
