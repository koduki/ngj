# NGJ MVP EBNF Grammar

> Draft for MVP (Issue #2)
> This grammar covers the minimal subset required for the MVP milestone.

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
data_decl       = "data" IDENT [ type_params ] "{" { field_decl } "}" ;
field_decl      = IDENT ":" type_expr [ "," ] ;

enum_decl       = "enum" IDENT [ type_params ] "{" variant_list "}" ;
variant_list    = variant { "," variant } [ "," ] ;
variant         = IDENT [ "(" field_list ")" ] ;
field_list      = field_decl { "," field_decl } ;

type_params     = "<" IDENT { "," IDENT } ">" ;
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
match_arm       = pattern "=>" ( expr | block ) [ "," ] ;

pattern         = "_"
                | literal
                | IDENT
                | constructor_pattern ;
constructor_pattern = type_path [ "(" pattern_fields ")" ] ;
pattern_fields  = pattern_field { "," pattern_field } ;
pattern_field   = IDENT [ ":" pattern ] ;
```

## Built-in Types and Constructors

```ebnf
(* Result<T, E> - built-in *)
result_expr     = "Ok" "(" expr ")" | "Err" "(" expr ")" ;

(* Option<T> - built-in *)
option_expr     = "Some" "(" expr ")" | "None" ;
```

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

## Explicitly Unsupported in MVP

The following are **not** part of the MVP grammar and must be rejected:

- Wildcard imports (`import foo.*`)
- Generic type parameters on user-defined types (except built-in Result/Option)
- Higher-order functions / closures
- Effect declarations other than `! {}`
- Ownership annotations (`&`, `&mut`, `resource`, `actor`)
- Capability types
- Concurrency primitives (`scope`, `fork`, `Channel`)
- Contract clauses (`requires`, `ensures`)
- Unsafe blocks
- Raw Java interop
