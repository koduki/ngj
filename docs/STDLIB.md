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

```
// Conceptual definition (built-in)
value data Int {
    // Internal: wraps JVM int
}

// Operations
fn add(a: Int, b: Int) -> Int effects() { ... }      // Overflow: task fault
fn sub(a: Int, b: Int) -> Int effects() { ... }      // Overflow: task fault
fn mul(a: Int, b: Int) -> Int effects() { ... }      // Overflow: task fault
fn div(a: Int, b: Int) -> Int effects() { ... }      // Division by zero: task fault
fn mod(a: Int, b: Int) -> Int effects() { ... }

// Checked operations (recoverable)
fn checkedAdd(a: Int, b: Int) -> Result<Int, OverflowError> effects() { ... }
fn checkedSub(a: Int, b: Int) -> Result<Int, OverflowError> effects() { ... }
fn checkedMul(a: Int, b: Int) -> Result<Int, OverflowError> effects() { ... }

// Comparisons
fn eq(a: Int, b: Int) -> Bool effects() { ... }      // a == b
fn lt(a: Int, b: Int) -> Bool effects() { ... }      // a < b
fn le(a: Int, b: Int) -> Bool effects() { ... }      // a <= b
fn gt(a: Int, b: Int) -> Bool effects() { ... }      // a > b
fn ge(a: Int, b: Int) -> Bool effects() { ... }      // a >= b

// Conversions
fn toInt(x: Int) -> Int effects() { x }
fn toLong(x: Int) -> Long effects() { ... }
fn toString(x: Int) -> String effects() { ... }
```

### 2.2 Long (64-bit signed integer)

```
value data Long {
    // Internal: wraps JVM long
}

// Same operations as Int, with Long types
fn add(a: Long, b: Long) -> Long effects() { ... }
fn checkedAdd(a: Long, b: Long) -> Result<Long, OverflowError> effects() { ... }
// ... etc.

// Conversions
fn toInt(x: Long) -> Result<Int, OverflowError> effects() { ... }  // May truncate
fn toLong(x: Long) -> Long effects() { x }
```

### 2.3 Bool (Boolean)

```
value data Bool {
    // Internal: wraps JVM boolean
}

// Operations
fn and(a: Bool, b: Bool) -> Bool effects() { ... }   // a && b (short-circuit in syntax)
fn or(a: Bool, b: Bool) -> Bool effects() { ... }    // a || b (short-circuit in syntax)
fn not(a: Bool) -> Bool effects() { ... }            // !a

// Equality
fn eq(a: Bool, b: Bool) -> Bool effects() { ... }
```

### 2.4 Byte (8-bit unsigned)

```
value data Byte {
    // Internal: wraps JVM byte (interpreted as unsigned 0..255)
}

// Operations for binary data processing
fn toInt(x: Byte) -> Int effects() { ... }           // Always succeeds (0..255)
fn fromInt(x: Int) -> Result<Byte, RangeError> effects() { ... }
```

### 2.5 Float / Double (IEEE 754)

```
value data Float {
    // Internal: wraps JVM float
}

value data Double {
    // Internal: wraps JVM double
}

// Note: NaN == NaN is false (IEEE 754 semantics)
// Use isNaN(x) for NaN checks
fn isNaN(x: Float) -> Bool effects() { ... }
fn isNaN(x: Double) -> Bool effects() { ... }
```

## 3. Option Type

`Option<T>` represents the presence or absence of a value. It is an **ordinary enum**, not a special built-in.

```
public enum Option<T> {
    Some(value: T),
    None()
}
```

### 3.1 Usage

```
// Creating Options
val present: Option<Int> = Some(42)
val absent: Option<Int> = None()

// Pattern matching (required for access)
match (maybeUser) {
    case Some(user) => { processUser(user) }
    case None() => { handleMissing() }
}

// Equality (value comparison)
Some(42) == Some(42)  // true
None() == None()      // true (Option<Int>)
Some(1) == Some(2)    // false
```

### 3.2 Standard Operations

```scala
// Map: transform the inner value if present
fn map<T, U>(opt: Option<T>, f: (T) -> U) -> Option<U> effects() {
    return match (opt) {
        case Some(v) => { Some(f(v)) },
        case None() => { None() }
    };
}

// FlatMap: chain Option-returning operations
fn flatMap<T, U>(opt: Option<T>, f: (T) -> Option<U>) -> Option<U> effects() {
    return match (opt) {
        case Some(v) => { f(v) },
        case None() => { None() }
    };
}

// GetOrElse: provide a default value
fn getOrElse<T>(opt: Option<T>, default: T) -> T effects() {
    return match (opt) {
        case Some(v) => { v },
        case None() => { default }
    };
}

// IsSome / IsNone
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

// ToResult: convert to Result
fn toResult<T, E>(opt: Option<T>, err: E) -> Result<T, E> effects() {
    return match (opt) {
        case Some(v) => { Ok(v) },
        case None() => { Err(err) }
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

```scala
// Map: transform the success value
fn map<T, U, E>(res: Result<T, E>, f: (T) -> U) -> Result<U, E> effects() {
    return match (res) {
        case Ok(v) => { Ok(f(v)) },
        case Err(e) => { Err(e) }
    };
}

// MapErr: transform the error value
fn mapErr<T, E, F>(res: Result<T, E>, f: (E) -> F) -> Result<T, F> effects() {
    return match (res) {
        case Ok(v) => { Ok(v) },
        case Err(e) => { Err(f(e)) }
    };
}

// FlatMap: chain Result-returning operations
fn flatMap<T, U, E>(res: Result<T, E>, f: (T) -> Result<U, E>) -> Result<U, E> effects() {
    return match (res) {
        case Ok(v) => { f(v) },
        case Err(e) => { Err(e) }
    };
}

// IsOk / IsErr
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

// ToOption: discard error info
fn toOption<T, E>(res: Result<T, E>) -> Option<T> effects() {
    return match (res) {
        case Ok(v) => { Some(v) },
        case Err(_) => { None() }
    };
}
```

## 5. List Type

`List<T>` is an **immutable** collection. All operations return new lists without modifying the original.

```
// Conceptual definition
public value data List<T> {
    // Internal: backed by persistent data structure or copy-on-write
}
```

### 5.1 Construction

```
// Empty list
val empty: List<Int> = Nil()

// From elements
val nums: List<Int> = listOf(1, 2, 3)

// Cons (prepend)
val withZero: List<Int> = Cons(0, nums)   // [0, 1, 2, 3]
```

### 5.2 Standard Operations

```
// Head: first element (may be absent)
fn head<T>(list: List<T>) -> Option<T> effects() { ... }

// Tail: all but first (may be empty)
fn tail<T>(list: List<T>) -> List<T> effects() { ... }

// Length
fn length<T>(list: List<T>) -> Int effects() { ... }

// IsEmpty
fn isEmpty<T>(list: List<T>) -> Bool effects() { ... }

// Get by index (may be absent)
fn get<T>(list: List<T>, index: Int) -> Option<T> effects() { ... }

// Map: transform each element
fn map<T, U>(list: List<T>, f: (T) -> U) -> List<U> effects() { ... }

// Filter: keep elements matching predicate
fn filter<T>(list: List<T>, p: (T) -> Bool) -> List<T> effects() { ... }

// FoldLeft: reduce from left
fn foldLeft<T, U>(list: List<T>, init: U, f: (U, T) -> U) -> U effects() { ... }

// Append
fn append<T>(a: List<T>, b: List<T>) -> List<T> effects() { ... }

// Reverse
fn reverse<T>(list: List<T>) -> List<T> effects() { ... }

// Contains (requires Eq)
fn contains<T>(list: List<T>, elem: T) -> Bool effects() { ... }
```

### 5.3 Value Semantics

```
val a: List<Int> = listOf(1, 2, 3)
val b: List<Int> = listOf(1, 2, 3)

a == b  // true (structural equality)

val c = Cons(0, a)  // a is unchanged
a == listOf(1, 2, 3)  // still true
```

## 6. Standard Error Types

```
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

## 7. Java Interop Boundary

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

```
// Java method: void process(List<Integer> numbers)
// NGJ call:
val nums: List<Int> = listOf(1, 2, 3)
JavaAdapter.process(nums)  // Int values boxed to Integer at boundary
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
| STDLIB-07 | checkedAdd/Sub/Mul return Result on overflow | 1 |
| STDLIB-08 | List.get returns Option<T> (not null/exception) | 1 |
| STDLIB-09 | Generic collections with value type elements | 1 |

## 9. Implementation Notes

### 9.1 JVM Representation

- Numeric value types map to JVM primitives (`int`, `long`, `boolean`)
- `Option<T>` and `Result<T,E>` are sealed value class hierarchies
- `List<T>` uses `java.util.List` internally with immutability enforced by API
- At Java boundary, boxing/unboxing is automatic

### 9.2 Performance Considerations

- Value types enable stack allocation and flattening (JVM optimization)
- Immutable lists use structural sharing where possible
- Generic specialization for value types is a future optimization
