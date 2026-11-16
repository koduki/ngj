# NGJ EBNF Grammar

> Draft v0.3 (Refs #2, #7)
> This grammar covers the core language including Valhalla foundation and enum ADT.

## Lexical Elements

```ebnf
(* Identifiers *)
IDENT           = ALPHA { ALPHA | DIGIT | "_" } ;
ALPHA           = "a".."z" | "A".."Z" ;
DIGIT           = "0".."9" ;

(* Keywords *)
KEYWORD         = "module" | "import" | "pub" | "fn" | "let" | "if" | "else" 
                | "match" | "return" | "data" | "enum" | "true" | "false" ;

(* Literals *)
INT_LITERAL     = [ "-" ] DIGIT { DIGIT } ;
LONG_LITERAL    = INT_LITERAL "L" ;
STRING_LITERAL  = '"' { STRING_CHAR } '"' ;
STRING_CHAR     = ESCAPE_SEQ | ( any char except '"' and '\' ) ;
ESCAPE_SEQ      = '\' ( 'n' | 'r' | 't' | '\' | '"' | 'u' HEX HEX HEX HEX ) ;
HEX             = DIGIT | "a".."f" | "A".."F" ;

(* Comments *)
LINE_COMMENT    = "//" { any char except newline } ;
BLOCK_COMMENT   = "/*" { any char } "*/" ;

(* Whitespace *)
WS              = " " | "\t" | "\n" | "\r" ;
```

## Module Structure

```ebnf
compilation_unit = module_decl { import_decl } { top_level_decl } ;

module_decl     = "module" module_path ;
module_path     = IDENT { "." IDENT } ;

import_decl     = "import" import_path ;
import_path     = module_path "." ( IDENT | "{" import_list "}" ) ;
import_list     = IDENT { "," IDENT } ;
(* NOTE: Wildcard imports like "import foo.*" are explicitly rejected *)

top_level_decl  = [ "pub" ] ( fn_decl | data_decl | enum_decl ) ;
```

## Type Declarations

```ebnf
(* Value types - default, identity-free *)
data_decl       = "data" IDENT [ type_params ] "{" { field_decl } "}" ;
record_decl     = "record" IDENT [ type_params ] "{" { field_decl } "}" ;  (* alias for data *)
field_decl      = IDENT ":" type_expr [ "," ] ;

(* Enum - sealed algebraic data type with payload-carrying variants *)
enum_decl       = "enum" IDENT [ type_params ] "{" variant_list "}" ;
variant_list    = variant { "," variant } [ "," ] ;
variant         = IDENT [ "{" field_list "}" ] ;  (* braces for payload *)
field_list      = field_decl { "," field_decl } [ "," ] ;

(* Identity types - explicit declaration required *)
identity_decl   = "identity" "class" IDENT [ type_params ] "{" { identity_member } "}" ;
identity_member = field_decl | var_field_decl ;
var_field_decl  = "var" IDENT ":" type_expr [ "," ] ;  (* mutable field *)

(* Resource/Actor - special identity types with ownership *)
resource_decl   = "resource" IDENT [ type_params ] "{" { identity_member } "}" ;
actor_decl      = "actor" IDENT [ type_params ] "{" { identity_member } "}" ;

(* Opaque value type *)
opaque_decl     = "opaque" "data" IDENT [ type_params ] "{" { field_decl } "}" ;

type_params     = "<" IDENT { "," IDENT } ">" ;
```

### Value Type Examples

```
// data - immutable value type (default)
data Point { x: Int, y: Int }

// enum - algebraic data type, all variants are value types
enum Shape {
    Circle { radius: Float },
    Rectangle { width: Float, height: Float },
    Point  // payload-less variant
}

// Generic enum - Result and Option are ordinary enums
enum Option<T> {
    Some { value: T },
    None
}

enum Result<T, E> {
    Ok { value: T },
    Err { error: E }
}
```

### Identity Type Examples

```
// Explicit identity class (rare, only when needed)
identity class Counter {
    var count: Int
}

// Resource with ownership semantics
resource FileHandle {
    path: String,
    var position: Long
}
```

## Function Declarations

```ebnf
fn_decl         = "fn" IDENT [ type_params ] "(" [ param_list ] ")" 
                  "->" type_expr effect_bound block ;

param_list      = param { "," param } ;
param           = IDENT ":" type_expr ;

effect_bound    = "!" "{" [ effect_list ] "}" ;
effect_list     = IDENT { "," IDENT } ;
(* NOTE: MVP only accepts empty effect bound: ! {} *)
```

## Type Expressions

```ebnf
type_expr       = simple_type | generic_type | unit_type ;

simple_type     = "Int" | "Long" | "Bool" | "String" | IDENT ;
generic_type    = ( "Result" | "Option" | IDENT ) "<" type_args ">" ;
type_args       = type_expr { "," type_expr } ;
unit_type       = "Unit" ;
```

## Statements and Expressions

```ebnf
block           = "{" { statement } [ expr ] "}" ;

statement       = let_stmt | expr_stmt | return_stmt ;
let_stmt        = "let" IDENT [ ":" type_expr ] "=" expr ;
expr_stmt       = expr ;
return_stmt     = "return" [ expr ] ;

expr            = or_expr [ "?" ] ;  (* postfix ? for Result/Option propagation *)

or_expr         = and_expr { "||" and_expr } ;
and_expr        = cmp_expr { "&&" cmp_expr } ;
cmp_expr        = add_expr [ cmp_op add_expr ] ;
cmp_op          = "==" | "!=" | "<" | "<=" | ">" | ">=" ;

add_expr        = mul_expr { ( "+" | "-" ) mul_expr } ;
mul_expr        = unary_expr { ( "*" | "/" | "%" ) unary_expr } ;

unary_expr      = [ "-" | "!" ] primary_expr ;

primary_expr    = literal
                | IDENT
                | hole
                | constructor_call
                | fn_call
                | field_access
                | if_expr
                | match_expr
                | block
                | "(" expr ")" ;

literal         = INT_LITERAL | LONG_LITERAL | STRING_LITERAL 
                | "true" | "false" | "()" ;

hole            = "?" IDENT ;

constructor_call = type_path "{" [ field_init_list ] "}" ;
type_path       = IDENT { "." IDENT } ;
field_init_list = field_init { "," field_init } [ "," ] ;
field_init      = IDENT ":" expr ;

fn_call         = ( IDENT | field_access ) "(" [ arg_list ] ")" ;
arg_list        = expr { "," expr } ;

field_access    = primary_expr "." IDENT ;

if_expr         = "if" expr block [ "else" ( if_expr | block ) ] ;

match_expr      = "match" expr "{" { match_arm } "}" ;
match_arm       = pattern [ guard ] "=>" ( expr | block ) [ "," ] ;
guard           = "if" expr ;

(* Pattern matching with nested/destructuring patterns *)
pattern         = "_"                           (* wildcard *)
                | literal                       (* literal pattern *)
                | IDENT                         (* binding *)
                | constructor_pattern           (* enum/data destructuring *)
                | "(" pattern ")"               (* parenthesized *)
                ;

(* Constructor pattern for enum variants and data types *)
constructor_pattern = type_path [ "{" pattern_fields "}" ] ;
pattern_fields  = pattern_field { "," pattern_field } [ "," ] ;
pattern_field   = IDENT [ ":" pattern ] ;       (* field: nested_pattern or just field *)

(* NOTE: Exhaustiveness checking is mandatory for enum matches.
   Missing variants produce compile error with specific diagnostics. *)
```

### Pattern Matching Examples

```
// Basic enum match (exhaustiveness required)
fn describe(shape: Shape) -> String ! {} {
    match shape {
        Circle { radius } => "circle with radius " + radius.toString(),
        Rectangle { width, height } => "rect " + width.toString() + "x" + height.toString(),
        Point => "point"
    }
}

// Nested destructuring
fn eval(expr: Expr) -> Int ! {} {
    match expr {
        Literal { value } => value,
        Add { left: Literal { value: a }, right: Literal { value: b } } => a + b,
        Add { left, right } => eval(left) + eval(right),
        Mul { left, right } => eval(left) * eval(right)
    }
}

// Guard conditions
fn classify(n: Int) -> String ! {} {
    match n {
        x if x < 0 => "negative",
        0 => "zero",
        x if x < 10 => "small",
        _ => "large"
    }
}

// Result/Option (ordinary enums, no special syntax)
fn process(opt: Option<Int>) -> Int ! {} {
    match opt {
        Some { value } => value * 2,
        None => 0
    }
}
```
```

## Standard Library Types (Not Built-ins)

Result and Option are ordinary generic enums defined in the standard library,
not language built-ins. They receive special treatment only for the `?` operator.

```ebnf
(* Result<T, E> - standard library enum *)
(* Definition in std:
   enum Result<T, E> {
       Ok { value: T },
       Err { error: E }
   }
*)
result_constructor = "Ok" "{" "value" ":" expr "}" 
                   | "Err" "{" "error" ":" expr "}" ;

(* Option<T> - standard library enum *)
(* Definition in std:
   enum Option<T> {
       Some { value: T },
       None
   }
*)
option_constructor = "Some" "{" "value" ":" expr "}" | "None" ;

(* The ? operator is sugar for match-and-propagate, not a type-level special case *)
(* expr? on Result<T, E> expands to:
   match expr {
       Ok { value } => value,
       Err { error } => return Err { error }
   }
*)
```

## Value Type Semantics

All types are value types by default:

| Type | Semantics | `==` behavior |
|------|-----------|---------------|
| `Int`, `Long`, `Bool`, etc. | Value type | Value comparison |
| `data` | Value type | Structural equality (field comparison) |
| `record` | Value type (alias for data) | Structural equality |
| `enum` | Value type | Variant tag + field comparison |
| `Option<T>`, `Result<T,E>` | Value type (enums) | Variant + field comparison |
| `identity class` | Identity type | Reference identity |
| `resource`, `actor` | Identity type | Reference identity |

**Key implications:**
- `Int(5) == Int(5)` is `true` (always, no "interning" concept)
- `Point(1, 2) == Point(1, 2)` is `true` for `data Point { x: Int, y: Int }`
- `Some { value: 42 } == Some { value: 42 }` is `true`
- No `null` in core language; absence is `Option<T>` with `None` variant

## Operator Precedence (highest to lowest)

| Level | Operators       | Associativity |
|-------|-----------------|---------------|
| 1     | `.` (field)     | Left          |
| 2     | `()` (call)     | Left          |
| 3     | `?` (propagate) | Postfix       |
| 4     | `-` `!` (unary) | Prefix        |
| 5     | `*` `/` `%`     | Left          |
| 6     | `+` `-`         | Left          |
| 7     | `<` `<=` `>` `>=` | Left        |
| 8     | `==` `!=`       | Left          |
| 9     | `&&`            | Left          |
| 10    | `||`            | Left          |

## Integer Semantics

- `Int`: Signed 32-bit integer. Range: -2147483648 to 2147483647.
- `Long`: Signed 64-bit integer. Range: -9223372036854775808 to 9223372036854775807.
- Integer literals without suffix default to `Int`.
- Integer literals with `L` suffix are `Long`.
- Negative literals: `-123` is parsed as unary minus applied to `123`.
- Overflow on `+`, `-`, `*` operations causes a **fault** (not wrap-around).
- Division by zero causes a **fault**.
- For recoverable arithmetic, use explicit `checkedAdd`, `checkedMul`, etc.

## String Semantics

- Strings are immutable UTF-8 sequences.
- Escape sequences: `\n` (newline), `\r` (carriage return), `\t` (tab), 
  `\\` (backslash), `\"` (double quote), `\uXXXX` (Unicode code point).
- String concatenation via `+` operator.
- Equality via `==` compares content.

## MVP Scope and Phased Features

### Phase 1 (MVP with Valhalla Foundation)

Supported:
- Value types: `data`, `record`, `enum` (ADT with payloads)
- Generic enums including user-defined (not just Result/Option)
- Pattern matching with exhaustiveness checking
- `Int`, `Long`, `Bool` as value types
- `Option<T>` for absence (no `null`)
- Valhalla value class output (JDK 28 EA + preview)

Must be rejected with clear diagnostics:
- Wildcard imports (`import foo.*`)
- Higher-order functions / closures
- Effect declarations other than `! {}`
- `null` literal

### Phase 2+

Deferred to later phases:
- Identity types (`identity class`, `resource`, `actor`)
- Ownership annotations (`&`, `&mut`)
- Capability types
- Concurrency primitives (`scope`, `fork`, `Channel`)
- Contract clauses (`requires`, `ensures`)
- Java interop adapters
- AOT profiles (Leyden, native-image)

## JVM Output Notes (Valhalla Foundation)

```
// NGJ source
data Point { x: Int, y: Int }

// JVM output (conceptual)
public final value class Point {
    private final int x;
    private final int y;
    // equals based on field values
}
```

```
// NGJ source
enum Shape {
    Circle { radius: Float },
    Rectangle { width: Float, height: Float },
    Point
}

// JVM output (conceptual)
public sealed value interface Shape 
    permits Shape.Circle, Shape.Rectangle, Shape.Point {}

public final value class Shape.Circle implements Shape {
    private final float radius;
}

public final value class Shape.Rectangle implements Shape {
    private final float width;
    private final float height;
}

public final value class Shape.Point implements Shape {}
```
