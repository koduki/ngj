# NGJ Standard Library — Value Type Foundation

> Status: v0.3 specification  
> Refs: [SPECIFICATION.md](SPECIFICATION.md) 2.3–2.4, Issue #7

This document defines the NGJ standard library types that are redesigned around value types (Valhalla foundation). All types are **value types by default** with no object identity.

## 1. Design Principles

### 1.1 Value Types Everywhere

- All standard library types are value types (identity-free)
- `==` compares by field values, not by reference identity
- No `null` in the type system; absence is represented by `Option<T>`
- Generic types (`List<Int>`, `Option<Point>`) work naturally with value types

### 1.2 No Null API

- No standard library function returns `null`
- No standard library function accepts `null`
- Java interop boundary handles null-to-Option conversion

### 1.3 Java Interop Boundary

When calling Java APIs or receiving values from Java:

| Java Side | NGJ Side |
|-----------|----------|
| `@Nullable T` | `Option<T>` |
| `@NonNull T` | `T` (null causes boundary fault) |
| Unknown nullability | `Option<T>` (defensive) |
| Primitive (`int`, `long`, `boolean`) | Value type (`Int`, `Long`, `Bool`) |
| Boxed (`Integer`, `Long`, `Boolean`) | Value type with null→None conversion |

## 2. Numeric Types

All numeric types are **value types**. There is no primitive/boxed distinction visible to users.

### 2.1 Int (32-bit signed integer)

`Int` is a built-in value type wrapping JVM `int`. It supports arithmetic operators (`+`, `-`, `*`, `/`, `%`) which fault on overflow/division-by-zero, and checked operations that return `Result`:

| Operation | Syntax/Function | Overflow behavior |
|-----------|-----------------|-------------------|
| Addition | `a + b` | Task fault |
| Subtraction | `a - b` | Task fault |
| Multiplication | `a * b` | Task fault |
| Division | `a / b` | Task fault (zero divisor) |
| Modulo | `a % b` | Task fault (zero divisor) |
| Checked add | `checkedAdd(a, b)` | Returns `Result<Int, OverflowError>` |
| Checked sub | `checkedSub(a, b)` | Returns `Result<Int, OverflowError>` |
| Checked mul | `checkedMul(a, b)` | Returns `Result<Int, OverflowError>` |

Comparisons use operators: `==`, `!=`, `<`, `<=`, `>`, `>=`.

Conversions *(staged — not yet implemented in v0.3 MVP)*:
- `toLong(x: Int) -> Long` — lossless widening *(staged)*
- `intToString(x: Int) -> String` — decimal representation *(staged)*

### 2.2 Long (64-bit signed integer)

`Long` is a built-in value type wrapping JVM `long`. It supports arithmetic operators (`+`, `-`, `*`, `/`, `%`, task fault on overflow/zero divisor) and comparison operators (`==`, `!=`, `<`, `<=`, `>`, `>=`).

> **MVP note:** In v0.3 MVP, `checkedAdd`/`checkedSub`/`checkedMul` accept `(Int, Int) -> Result<Int, OverflowError>` only; checked variants and conversions for `Long` (`longToInt`, `longToString`) are *staged, not yet implemented*. (Distinct non-overloaded names will be used because NGJ disallows function overloading.)

Staged operations *(not yet implemented in v0.3 MVP)*:
- `checkedAddLong(a: Long, b: Long) -> Result<Long, OverflowError>` (and `checkedSubLong`, `checkedMulLong`)
- `longToInt(x: Long) -> Result<Int, OverflowError>` — narrowing, returns `Err` on out-of-range
- `longToString(x: Long) -> String` — decimal representation

### 2.3 Bool (Boolean)

`Bool` is a built-in value type wrapping JVM `boolean`. Logical operators: `&&` (short-circuit and), `||` (short-circuit or), `!` (not). Equality uses `==` and `!=`.

### 2.4 Byte (8-bit unsigned) — *staged, not yet implemented*

> **Note:** `Byte` is recognized as a staged type name in name resolution, but arithmetic (`NGJ-TYPE-006`) and code generation are not yet implemented in v0.3 MVP.

`Byte` is a built-in value type wrapping JVM `byte` (interpreted as unsigned 0..255).

Conversions *(staged)*:
- `byteToInt(x: Byte) -> Int` — always succeeds (0..255)
- `intToByte(x: Int) -> Result<Byte, RangeError>` — returns `Err` if out of 0..255

### 2.5 Float / Double (IEEE 754) — *staged, not yet implemented*

> **Note:** `Float` and `Double` are recognized as staged type names in name resolution, but floating-point literals are not lexed (e.g., `3.14159` yields `NGJ-PARSE-007`), arithmetic produces `NGJ-TYPE-006`, and code generation is not yet implemented in v0.3 MVP.

`Float` and `Double` are built-in value types wrapping JVM `float` and `double`. They follow IEEE 754 semantics where `NaN == NaN` is `false`.

NaN checks *(staged)*:
- `floatIsNaN(x: Float) -> Bool`
- `doubleIsNaN(x: Double) -> Bool`

## 3. Option Type

`Option<T>` represents the presence or absence of a value. It is an **ordinary enum**, not a special built-in.

```scala
public enum Option<T> {
    Some(value: T),
    None()
}
```

### 3.1 Usage

```scala
// Creating Options
val present: Option<Int> = Some(42);
val absent: Option<Int> = None();

// Pattern matching (required for access)
match (maybeUser) {
    case Some(user) => { processUser(user); },
    case None() => { handleMissing(); }
};

// Equality (value comparison)
Some(42) == Some(42);  // true
None() == None();      // true
Some(1) == Some(2);    // false
```

### 3.2 Standard Operations

In v0.3 MVP, user-defined generic functions and lambda codegen are staged. The following `Option` helpers are provided as built-in compiler intrinsics:

**Implemented built-ins (v0.3 MVP):**
- `getOrElse(opt: Option<T>, default: T) -> T` — returns inner value or `default`
- `isSome(opt: Option<T>) -> Bool` — `true` if `Some`
- `isNone(opt: Option<T>) -> Bool` — `true` if `None()`
- `toResult(opt: Option<T>, err: E) -> Result<T, E>` — converts `Some(v)` to `Ok(v)` and `None()` to `Err(err)`

**Conceptual definitions (including staged higher-order operations `map`, `flatMap`):**

```scala
// Conceptual signatures (user generic fn & lambda codegen are post-MVP)

// GetOrElse (implemented built-in): provide a default value
fn getOrElse<T>(opt: Option<T>, default: T) -> T effects() {
    return match (opt) {
        case Some(v) => { v },
        case None() => { default }
    };
}

// IsSome / IsNone (implemented built-ins)
fn isSome<T>(opt: Option<T>) -> Bool effects() {
    return match (opt) {
        case Some(_) => { true },
        case None() => { false }
    };
}

fn isNone<T>(opt: Option<T>) -> Bool effects() {
    return match (opt) {
        case Some(_) => { false },
        case None() => { true }
    };
}

// ToResult (implemented built-in): convert to Result
fn toResult<T, E>(opt: Option<T>, err: E) -> Result<T, E> effects() {
    return match (opt) {
        case Some(v) => { Ok(v) },
        case None() => { Err(err) }
    };
}

// Staged (not yet implemented in MVP): Map / FlatMap
fn map<T, U>(opt: Option<T>, f: (T) -> U) -> Option<U> effects() {
    return match (opt) {
        case Some(v) => { Some(f(v)) },
        case None() => { None() }
    };
}

fn flatMap<T, U>(opt: Option<T>, f: (T) -> Option<U>) -> Option<U> effects() {
    return match (opt) {
        case Some(v) => { f(v) },
        case None() => { None() }
    };
}
```

## 4. Result Type

`Result<T, E>` represents success or failure. It is an **ordinary enum**.

```scala
public enum Result<T, E> {
    Ok(value: T),
    Err(error: E)
}
```

### 4.1 The `?` Operator

The `?` operator provides early return on `Err`:

```scala
fn process(input: String) -> Result<Output, ProcessError> effects() {
    val parsed = parse(input)?;      // If Err, returns Err immediately
    val validated = validate(parsed)?;
    return Ok(transform(validated));
}

// Desugars to:
fn process(input: String) -> Result<Output, ProcessError> effects() {
    val parsed = match (parse(input)) {
        case Ok(v) => { v },
        case Err(e) => { return Err(e); }   // Early return
    };
    val validated = match (validate(parsed)) {
        case Ok(v) => { v },
        case Err(e) => { return Err(e); }
    };
    return Ok(transform(validated));
}
```

**Important**: `?` is **not** supported on `Option<T>` in v0.3 (`NGJ-TYPE-020`). Use `match` or `toResult` explicitly.

### 4.2 Standard Operations

**Implemented built-ins (v0.3 MVP):**
- `isOk(res: Result<T, E>) -> Bool`
- `isErr(res: Result<T, E>) -> Bool`
- `toOption(res: Result<T, E>) -> Option<T>`

**Conceptual definitions (including staged higher-order operations `map`, `mapErr`, `flatMap`):**

```scala
// Conceptual signatures (user generic fn & lambda codegen are post-MVP)

// IsOk / IsErr (implemented built-ins)
fn isOk<T, E>(res: Result<T, E>) -> Bool effects() {
    return match (res) {
        case Ok(_) => { true },
        case Err(_) => { false }
    };
}

fn isErr<T, E>(res: Result<T, E>) -> Bool effects() {
    return match (res) {
        case Ok(_) => { false },
        case Err(_) => { true }
    };
}

// ToOption (implemented built-in): discard error info
fn toOption<T, E>(res: Result<T, E>) -> Option<T> effects() {
    return match (res) {
        case Ok(v) => { Some(v) },
        case Err(_) => { None() }
    };
}

// Staged (not yet implemented in MVP): Map / MapErr / FlatMap
fn map<T, U, E>(res: Result<T, E>, f: (T) -> U) -> Result<U, E> effects() {
    return match (res) {
        case Ok(v) => { Ok(f(v)) },
        case Err(e) => { Err(e) }
    };
}

fn mapErr<T, E, F>(res: Result<T, E>, f: (E) -> F) -> Result<T, F> effects() {
    return match (res) {
        case Ok(v) => { Ok(v) },
        case Err(e) => { Err(f(e)) }
    };
}

fn flatMap<T, U, E>(res: Result<T, E>, f: (T) -> Result<U, E>) -> Result<U, E> effects() {
    return match (res) {
        case Ok(v) => { f(v) },
        case Err(e) => { Err(e) }
    };
}
```

## 5. List Type

`List<T>` is an **immutable** collection. All operations return new lists without modifying the original.

In the JVM implementation, `List<T>` is backed by an unmodifiable `java.util.List` (`List.of()` for `Nil()` / empty `listOf()`, and `Collections.unmodifiableList(...)` for non-empty `listOf(...)`, `Cons(head, tail)`, and `for-yield`; `Cons` copies elements into a new unmodifiable list). [SPECIFICATION.md](SPECIFICATION.md) section 2.4.5's `Cons`/`Nil` ADT definition is conceptual only.

### 5.1 Construction

```scala
// Empty list
val empty: List<Int> = Nil();

// From elements
val nums: List<Int> = listOf(1, 2, 3);

// Cons (prepend)
val withZero: List<Int> = Cons(0, nums);   // [0, 1, 2, 3]
```

### 5.2 Standard Operations

**Implemented built-ins (v0.3 MVP):**
- `head(list: List<T>) -> Option<T>` — first element (may be absent)
- `get(list: List<T>, index: Int) -> Option<T>` — element by index (may be absent)
- `length(list: List<T>) -> Int` — number of elements
- `isEmpty(list: List<T>) -> Bool` — `true` if empty

**Staged operations *(not yet implemented in v0.3 MVP)*:**

```scala
// Conceptual signatures (not yet implemented in MVP)
fn tail<T>(list: List<T>) -> List<T> effects()
fn map<T, U>(list: List<T>, f: (T) -> U) -> List<U> effects()
fn filter<T>(list: List<T>, p: (T) -> Bool) -> List<T> effects()
fn foldLeft<T, U>(list: List<T>, init: U, f: (U, T) -> U) -> U effects()
fn append<T>(a: List<T>, b: List<T>) -> List<T> effects()
fn reverse<T>(list: List<T>) -> List<T> effects()
fn contains<T>(list: List<T>, elem: T) -> Bool effects()
```

*(Note: For eager mapping/transformation in v0.3 MVP, use `for (x <- list) yield { ... }`.)*

### 5.3 Value Semantics

```scala
val a: List<Int> = listOf(1, 2, 3);
val b: List<Int> = listOf(1, 2, 3);

a == b;  // true (structural equality via Objects.equals)

val c = Cons(0, a);     // a is unchanged
a == listOf(1, 2, 3);   // still true
```

## 6. Standard Error Types

```scala
public enum OverflowError {
    IntOverflow(),
    LongOverflow()
}

public enum RangeError {
    OutOfRange(message: String)
}

public enum IndexError {
    IndexOutOfBounds(index: Int, length: Int)
}
```

## 7. Java Interop Boundary — *staged (Stage 2)*

> **Note:** Java boundary adapters and `effects(...)` capabilities are Stage 2 features, not yet exposed in v0.3 MVP source syntax.

### 7.1 Null-to-Option Conversion (NULL-02)

When receiving values from Java that may be null:

```scala
// Java method: @Nullable User findUser(String id)
// NGJ adapter generates:
fn findUser(id: String) -> Option<User> effects(DbRead) {
    val javaResult = JavaAdapter.findUser(id);  // May be null
    return JavaBoundary.nullToOption(javaResult); // Null -> None(), non-null -> Some(value)
}
```

### 7.2 Boxing at Boundary

When passing NGJ value types to Java generics:

```scala
// Java method: void process(List<Integer> numbers)
// NGJ call:
val nums: List<Int> = listOf(1, 2, 3);
JavaAdapter.process(nums);  // Int values boxed to Integer at boundary
```

### 7.3 Boundary Conversion Rules

| NGJ Type | Java Generic | Boundary Action |
|----------|--------------|-----------------|
| `Int` | `Integer` | Auto-box |
| `Long` | `Long` | Auto-box |
| `Bool` | `Boolean` | Auto-box |
| `Option<T>` | `@Nullable T` | None→null, Some(v)→v |
| `List<Int>` | `List<Integer>` | Deep box elements |
| User value type | Java object | Direct (value class) |

## 8. Validation IDs

| ID | Description | Stage |
|----|-------------|-------|
| STDLIB-01 | Int/Long/Bool as value types with structural equality | 1 |
| STDLIB-02 | Option<T> as ordinary enum, None() with no payload | 1 |
| STDLIB-03 | Result<T,E> as ordinary enum, ? propagation | 1 |
| STDLIB-04 | List<T> value semantics (equality, immutability) | 1 |
| STDLIB-05 | No null in standard library APIs | 1 |
| NULL-02 | Java boundary null-to-Option conversion | 2 |
| STDLIB-06 | Numeric overflow detection (task fault) | 1 |
| STDLIB-07 | checkedAdd/Sub/Mul for `Int` return Result on overflow | 1 |
| STDLIB-08 | `get(list, index)` returns Option<T> (not null/exception) | 1 |
| STDLIB-09 | Generic collections with value type elements | 1 |

## 9. Implementation Notes

### 9.1 JVM Representation

- Numeric value types map to JVM primitives (`int`, `long`, `boolean`; boxed to `Integer`/`Long`/`Boolean` in generic payload/list positions)
- `Option<T>` and `Result<T,E>` are generated as `ngj.runtime.Option` / `ngj.runtime.Result` sealed class hierarchies emitted as JEP 401 value classes (no `ACC_IDENTITY` flag, `ACC_STRICT_INIT`/`ACC_STRICT` fields) with `--target=valhalla` (default), and ordinary classes with `ACC_IDENTITY` under `--target=valhalla-compat` / `java25`
- `List<T>` uses unmodifiable `java.util.List` (`List.of()` for empty lists, `Collections.unmodifiableList(...)` for non-empty `listOf`/`Cons`/`for-yield`) and compares elements via `Objects.equals`
- At Java boundary, boxing/unboxing is automatic

### 9.2 Performance Considerations

- Value types enable stack allocation and flattening (JVM optimization)
- Empty lists share `List.of()`; `Cons(head, tail)` copies elements into a new unmodifiable list
- Generic specialization for value types is a future optimization
