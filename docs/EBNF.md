# NGJ v0.3 grammar

> Normative target for the syntax migration. The compiler at the base commit still implements v0.2 forms.
> The archived [v0.2 grammar](EBNF_V02.md) and existing compiler fixtures document the migration baseline.
> Valhalla foundation and enum ADT semantics: Refs #7.

## 1. Canonical forms

Every construct has one source spelling. Package/import declarations end with semicolons. Blocks use braces. Newlines are whitespace. Keywords cannot be used as identifiers, including path segments.

- Declarations use `package`, `public`, `fn`, `record`, `enum`, `val`, `var`.
- Type names are `Int`, `Long`, `Bool`, `String`, `Unit`, `Result<T,E>`, `Option<T>`.
- Functions require parameter types, result types and `effects(...)`, including private functions.
- Arguments and constructor fields are positional, in declaration order. Named/default/variadic arguments are deferred.
- Imports name one symbol per declaration. Grouped imports, aliases and wildcards are deferred.
- Value-producing blocks use a tail expression. `return` is absent.
- Assignment is a statement. Only local `var` bindings can be reassigned when the sequential profile is enabled.
- No overloads, implicit conversions, operator overloading, infix calls, inheritance or implicit parameters.
- Member access and qualified variants use `.`. Explicit type arguments in expressions use `::<...>`.
- Parameterless variants are written `Error.Invalid()`, including in patterns. Built-in constructors follow the same call rule: `Ok(v)`, `Err(e)`, `Some(v)`, `None()`.

## 2. Lexical rules

Identifiers use ASCII letters or underscore followed by letters, digits or underscores. A lone underscore is reserved for wildcard patterns. Source is UTF-8. Comments are line comments or non-nested block comments ending at the first closing delimiter.

Decimal integer tokens have no sign. An optional uppercase L suffix selects Long. Unary minus is parsed separately. The checker recognizes directly negated boundary literals so Int.MIN_VALUE and Long.MIN_VALUE remain expressible.

Strings use double quotes and the escapes `\\n`, `\\r`, `\\t`, `\\\\`, `\\"`, and `\\uXXXX`. Decode UTF-16 escape pairs to Unicode scalar values and reject unpaired surrogates. Runtime representation is private to the backend. Raw newlines and string interpolation are deferred.

Tokenize punctuation by longest match, including `=>`, `->`, `<-`, `::`, `<=`, `>=`, `==`, `!=`, `&&`, `||`. Shift operators are deferred, so `>>` is two closing angle tokens. A question mark and its following identifier are separate tokens. Whitespace does not select hole versus propagation.

## 3. Core grammar

Notation: brackets denote optional grammar items, braces denote repetition, quoted strings are terminals. IDENT, INT, LONG and STRING are lexical tokens. EOF is end of input. Comma-separated lists have no trailing comma.

```ebnf
file = "package" path ";" { importDecl } { declaration } EOF ;
path = IDENT { "." IDENT } ;
importDecl = "import" path ";" ;
declaration = [ "public" ] ( function | record | enum ) ;
function = "fn" IDENT "(" [ parameters ] ")" "->" type effects block ;
parameters = parameter { "," parameter } ;
parameter = IDENT ":" type ;
effects = "effects" "(" [ path { "," path } ] ")" ;
record = "record" IDENT "(" [ parameters ] ")" ";" ;
enum = "enum" IDENT "{" variant { "," variant } "}" ;
variant = IDENT "(" [ parameters ] ")" ;
type = path [ "<" type { "," type } ">" ] ;

block = "{" { statement } [ expression ] "}" ;
statement = binding | assignment | expression ";" ;
binding = ( "val" | "var" ) IDENT [ ":" type ] "=" expression ";" ;
assignment = IDENT "=" expression ";" ;
expression = logicalOr ;
logicalOr = logicalAnd { "||" logicalAnd } ;
logicalAnd = equality { "&&" equality } ;
equality = comparison [ ( "==" | "!=" ) comparison ] ;
comparison = addition [ ( "<" | "<=" | ">" | ">=" ) addition ] ;
addition = multiplication { ( "+" | "-" ) multiplication } ;
multiplication = unary { ( "*" | "/" | "%" ) unary } ;
unary = ( "-" | "!" ) unary | postfix ;
postfix = primary { suffix } ;
suffix = "." IDENT | call | "?" ;
call = [ "::" "<" type { "," type } ">" ] "(" [ arguments ] ")" ;
arguments = expression { "," expression } ;
primary = INT | LONG | STRING | "true" | "false"
        | IDENT | "?" IDENT | "(" [ expression ] ")"
        | block | conditional | matching ;
conditional = "if" "(" expression ")" block [ "else" block ] ;
matching = "match" "(" expression ")" "{" arm { "," arm } "}" ;
arm = "case" pattern "=>" block ;
pattern = "_" | IDENT | INT | LONG | STRING | "true" | "false"
        | "-" ( INT | LONG ) | constructorPattern ;
constructorPattern = path "(" [ pattern { "," pattern } ] ")" ;
```

The empty parentheses expression is Unit, written `()`. A bare identifier pattern binds a value. A constructor pattern always has parentheses, so the parser can distinguish bindings without consulting types. Nested patterns are permitted. Guards and alternative patterns are deferred.

The grammar includes var/assignment for the sequential profile. The first core migration may reject them as unsupported until that profile is enabled.

The core recognizes type application syntactically, then checks the supported type constructors. The first compiler profile supports Result and Option applications. User-defined generic declarations and explicit type-argument calls require a later profile.

## 4. Deterministic parsing and typing rules

The block notation above has a shared expression prefix. A recursive-descent parser handles it as follows.

1. On val/var, parse a binding.
2. On IDENT followed by a single equals token, parse assignment.
3. Otherwise parse one expression. A following semicolon ends a statement. A following closing brace makes it the tail. Any other token produces a missing-semicolon diagnostic.

Declarations cannot appear inside blocks. Expression statements must have Unit type. This prevents silently discarding a value or Result. A trailing semicolon on a required Int result produces a diagnostic identifying the lost result, with the enclosing return type.

An if with else requires both branch types to agree under the expected type. An if without else requires a Unit then-branch and produces Unit. Braces are mandatory even for nested else-if. Match checks all variants and compatible arm result types. There are no implicit numeric widenings.

A binding's name is available only after its initializer. Local declarations cannot shadow active local/parameter/import names in the initial profile. Type and term declarations share a namespace within a package, except record constructors, which are the value construction operation of their type.

Member access, calls and postfix question marks form one left-to-right chain with tighter precedence than unary and arithmetic operations. `f()?.field` is parsed as `(f()?).field`; the lexer emits separate question and dot tokens. NGJ has no optional-access operator. `f()??` propagates two nested Results in sequence and is checked one operation at a time.

`a + f()?` means `a + (f()?)`. `?body` is a hole in primary position. `f<T>(x)` is not an explicit generic call; use `f::<T>(x)`.

## 5. Error propagation boundaries

Postfix `?` accepts Result only. On Ok, it yields the payload. On Err, it exits the nearest function/lambda body with the same error type. Option is handled by match or an explicit library conversion to Result. No implicit error conversion is allowed.

Ordinary blocks, if, match, while, build and scope do not introduce a function boundary. Leaving them through `?` must perform their cleanup. Lambdas and fork bodies do introduce a boundary. An Err in fork becomes the child result, not an exit from the parent. Source positions and boundary IDs must survive lowering.

## 6. Sequential loops and lambdas (post-core profile)

Loop semantics follow the Scala distinction between for-yield, for and while. Initial for support is restricted to standard eager List, one generator and no guard. Input is evaluated once, elements are visited in order, and an empty input performs no body evaluation. Lazy streams, Option/Result comprehensions, multiple generators and arbitrary user-provided map/flatMap protocols are deferred.

```ebnf
forExpression = "for" "(" IDENT "<-" expression ")" [ "yield" ] block ;
whileExpression = "while" "(" expression ")" block ;
lambda = "(" [ parameters ] ")" "=>" block ;
```

These forms extend primary expressions. Lambda parameters always have explicit types. A colon after the first identifier inside parentheses identifies the lambda parameter grammar; `() =>` is the zero-parameter lambda. Parenthesized expressions remain distinct. No tuple expressions or shorthand lambda arguments are introduced.

- for-yield with List<T> input and U body returns List<U>, eagerly.
- for without yield requires a Unit body and returns Unit.
- while requires Bool condition and Unit body and returns Unit. Condition is reevaluated before every iteration.
- The for body is a callback boundary. To keep result typing explicit, postfix `?` is rejected in that body, except inside a separately declared nested lambda. Return Result values explicitly, producing List<Result<...>>, or use a typed traversal library operation.
- Nested while blocks in a for body inherit that restriction. A while in a function may propagate to that function.
- Mutable captures in ordinary lambdas and for callbacks are initially rejected. Read-only val captures are allowed, subject to ownership. Local var declared inside the callback is allowed. Accumulating mutable state uses while in the owning function.
- break/continue, labeled exits and non-local returns are deferred.

## 7. System features and implementation stages

The pure core is the first migration target. resource/actor/build/scope/fork, borrowing, lifetimes, contracts, trace/event and derive remain staged language features in SPECIFICATION.md. Their checks must be implemented before their syntax is enabled. The core grammar makes no claim to cover their full productions.

Public shape decisions for those stages:
- scope/build share the ordinary block's propagation boundary; fork owns a separate boundary.
- build names its builder explicitly rather than introducing an implicit this.
- Effects at function boundaries remain explicit; effect polymorphism is deferred.
- Lifetime/ownership rules begin with lexical scopes.
- Library types (Secret, Capability, typestate markers) reuse ordinary type application.
- No general macros or user-defined syntax extensions.

## 8. Valhalla foundation and value type semantics

NGJ targets Valhalla-capable JDK (JDK 28 EA + `--enable-preview`) as the foundation. All types declared with `record` and `enum` are **value types** (identity-free) by default. See [SPECIFICATION.md](SPECIFICATION.md) section 1.3 and 2.2.

### 8.1 Value types are the default

| Declaration | Semantics | `==` behavior |
|-------------|-----------|---------------|
| `record` | Immutable, identity-free | Structural equality (field values) |
| `enum` variant | Immutable, identity-free | Variant tag + field comparison |
| `Int`, `Long`, `Bool` | Value type | Value comparison |
| `Option<T>`, `Result<T,E>` | Library enums (value types) | Variant + field comparison |

**Key implications:**
- `Point(1, 2) == Point(1, 2)` is `true` for `record Point(x: Int, y: Int)`
- `Some(42) == Some(42)` is `true`
- No `null` in core language; absence is `Option<T>` with `None()`

### 8.2 Enum as algebraic data type

The core grammar's `enum` and `variant` rules support payload-carrying variants:

```
enum Shape {
    Circle(radius: Float),
    Rectangle(width: Float, height: Float),
    Point()
}
```

Each variant is a value type. `Result` and `Option` are ordinary library enums, not special built-ins:

```
enum Option<T> {
    Some(value: T),
    None()
}

enum Result<T, E> {
    Ok(value: T),
    Err(error: E)
}
```

### 8.3 Match exhaustiveness

Match expressions **must** cover all enum variants. Missing variants produce a compile error with **precise diagnostics** naming the missing variants. This is critical for the AI fix loop.

```
// Error: missing variant 'Point' in match expression
fn area(shape: Shape) -> Float effects() {
    match (shape) {
        case Circle(r) => { 3.14159 * r * r }
        case Rectangle(w, h) => { w * h }
        // Point is missing → compile error with diagnostic
    }
}
```

### 8.4 JVM representation

Records and enum variants compile to Valhalla value classes in a sealed hierarchy:

```
// NGJ
enum Shape { Circle(r: Float), Rectangle(w: Float, h: Float), Point() }

// JVM output (conceptual)
// JEP 401 has no value interface, so we use sealed abstract value class
abstract sealed value class Shape permits Shape$Circle, Shape$Rectangle, Shape$Point {}
final value class Shape$Circle extends Shape { float r; }
final value class Shape$Rectangle extends Shape { float w; float h; }
final value class Shape$Point extends Shape {}
```

### 8.5 Identity types (staged feature)

Identity types (`identity class`, `resource`, `actor`) require explicit declaration and are staged features. See SPECIFICATION.md section 2.2.2.

## 9. Migration acceptance

See [SYNTAX_MIGRATION.md](SYNTAX_MIGRATION.md) for change mapping, implementation work and positive/negative cases. This EBNF has undergone document review only. Parser conformance and AI-generation measurements are required before declaring v0.3 supported.
