# NGJ — Concept & Language Specification

> Draft v0.3 / 2026-11-17
> Status: v0.3 core syntax and type checking implemented; code generation targets JDK 28 EA with `--enable-preview` for JEP 401 value classes.
> Baseline: Valhalla対応JDK（JDK 28 EA + `--enable-preview`、互換プロファイルとしてJava 25 LTS bytecode出力もサポート）。Valhalla（JEP 401/539）と最新JVM基盤（Loom確定版、Integrity by Default、Project Leyden、Panama FFM）はオプションではなく、言語設計の前提である。

## 1. 目的・適用範囲

NGJは、AIエージェントが生成・修正することを第一級の利用形態とする、静的型付け・式指向のJVM言語である。人間がレビューできるソースコードを保ち、コンパイラが型・所有権・副作用・契約の情報を生成側に返す。Javaのライブラリ資産を境界アダプター経由で利用し、サーバー、CLI、バイナリ処理を主な用途とする。

### 1.1 設計原則

1. **Value first (Valhalla foundation):** 不変の値と、同一性・状態・寿命を持つリソースを分離する。Project Valhallaのvalue classes（JEP 401）と厳格なフィールド初期化（JEP 539）を言語設計の基盤とし、プリミティブ型とユーザー定義値型を統一する。NGJにおける`Int`/`Long`/`Bool`等はvalue型であり、boxed/primitive の二重性をユーザーに見せない。VMによるフラット化・スカラ化は性能最適化であり、言語の意味論は値の等価性と不変性に基づく。
2. **Colorless structured concurrency:** `scope` / `fork` と仮想スレッド（`StructuredTaskScope` JEP 543、`ScopedValue` JEP 506、non-pinning `synchronized` JEP 491）によって、`async/await`なしの直接的な記述を提供する。
3. **Checkable authority:** 型・Effect・Capabilityで、実行可能な操作と対象を表す。安全性をソース中の自己申告に依存させず、JVMのIntegrity by Default路線（`Unsafe`・JNI・`final`改変・動的エージェントの既定制限）と連動する。
4. **Compiler as an agent interface:** 型付き穴、部分プログラム検査、構造化診断、意味に基づく文脈取得をコンパイラの公開機能とする。
5. **Observable and verifiable behavior:** OTel/JFR（JEP 520/536）、契約、反例、I/O再実行を接続する。観測と保証を区別する。
6. **Static interop and AOT:** NGJ自身は実行時リフレクションや動的クラス生成に依存せず、Java境界・FFM境界とProject Leyden（JEP 483/514/515/516/544）等のAOT要件をビルド時に検査する。

AIを言語ランタイムやコンパイラの必須依存にはしない。同じソース・依存・ツールチェーンからのビルドにLLM呼び出しは不要。エージェント専用構文やプロンプトDSL、任意の依存型、完全な自動証明はv0.3の必須要件ではない。

### 1.2 Valhalla基盤 — 型システムの根幹

NGJはProject Valhallaのvalue classesを言語設計の**基盤**とする。これはオプションの最適化バックエンドではなく、型システムと標準ライブラリの根幹である。

**設計原則:**
- **Value型がデフォルト:** すべてのユーザー定義型はvalue型として設計する。同一性（identity）は明示的に必要な場合のみ宣言する
- **Primitive/Boxed分離の廃止:** `Int`/`Long`/`Bool`等はvalue型であり、ユーザーにprimitive/boxedの二重性を見せない
- **nullの廃止:** コア言語に`null`リテラルは存在しない。不在は`Option<T>`で表現する
- **値等価性がデフォルト:** `==`はすべてのvalue型でフィールド値による等価性比較
- **厳格な初期化と不変性のVM保証:** JEP 539（Strict Field Initialization, `ACC_STRICT_INIT`）とJEP 513（Flexible Constructor Bodies）を前提とし、未初期化状態（`0`/`null`）の露出やJava境界からのdeep reflection・`ObjectInputStream`による不変フィールド改変をJVM検証レベルで防ぐ
- **ジェネリクスはvalue型を含む:** `List<Int>`は言語レベルで保証された機能であり、実装の最適化状況に依存しない

**Identity型が必要な場面:**
- `resource`: 寿命・所有権・クリーンアップを持つ型
- `actor`: 隔離された可変状態を持つ型
- 明示的な`identity`宣言: 可変性、同期、参照比較が必要な場合
- Java境界: Java側identity objectとの相互運用

**実装状況（2026年11月時点）:**

| 言語機能 | 仕様上の扱い | JVM実装基盤 | 状態 |
|---------|-------------|------------|------|
| Value型デフォルト | 必須 | JEP 401 Value Objects | JDK 28 Preview |
| 厳格な初期化・`final`改変防御 | 必須 | JEP 539 Strict Field Initialization (`ACC_STRICT_INIT`) | JDK 28 Preview（JEP 401と同時統合） |
| コンストラクタ事前検証 | 標準対応 | JEP 513 Flexible Constructor Bodies | JDK 25 Final |
| Null廃止 / Option | 必須 | JEP draft 8316779 Null-Restricted Types | Draft |
| Primitive統一 | 必須 | JEP 402 Enhanced Primitive Boxing | Draft |
| ジェネリック特殊化 | 意味論保証、性能は実装依存 | Parametric JVM | 研究段階 |

**JDKピン方針:**
- 開発時は JDK 28 EA + `--enable-preview` をターゲットとする（互換検証用に `--target=valhalla-compat` / `java25` も提供）
- 仕様はJDKの現状に妥協せず理想を記述し、実装がそれを追う
- JVM機能の不足部分はコンパイラ側で補完（ランタイムチェック、ラッパー生成等）

**リスクと移行:**
詳細は本文書末尾の「オープンクエスチョンとリスク」節を参照。

### 1.3 文書の読み方と保証範囲

本文の「必須」「拒否」は実装が満たすべき規範要件。「候補」「後段」は未実装の研究・拡張項目。

- 本文: 言語・ランタイム・Java境界の規範仕様。
- [AGENT_TOOLING.md](AGENT_TOOLING.md): CLI/診断/部分検査のプロトコル。
- [VALIDATION.md](VALIDATION.md): 適合性試験、性能検証、AI生成評価、実装順。
- [RESEARCH.md](RESEARCH.md): 研究との対応、出典と証拠の限界。
- [examples/README.md](../examples/README.md): サンプルの前提と未定義アプリAPI。

型検査は業務仕様の正しさ、停止性、外部サービスの応答、タイミング側信道を一般には保証しない。安全性は、検査器・標準ランタイム・許可されたアダプター・ホストのポリシーを信頼基盤とする。任意のJava/JNIコードを同一プロセスにロードした場合に、型だけで隔離できるとは主張しない。

### 1.4 コアJVM基盤前提（Loom・Integrity・Leyden・Panama・Lilliput）

Valhalla（JEP 401/539）に加え、NGJは以下のJDK 24〜28仕様およびOpenJDKプロジェクトをランタイム・コンパイラの中核前提とする。

| 領域 | JVM仕様 / Project | 状態（2026年11月） | NGJにおける役割 |
|---|---|---|---|
| **バイトコード出力** | Class-File API（JEP 484） | JDK 24 Final | 外部バイトコード生成ライブラリなしでの直接`.class`生成 |
| **構造化並行性・文脈** | Virtual Threads + No-Pinning（JEP 491）<br>`ScopedValue`（JEP 506）<br>`StructuredTaskScope` / `Joiner`（JEP 543） | JEP 491: JDK 24 Final<br>JEP 506: JDK 25 Final<br>JEP 543: JDK 28 Final | `scope`/`fork`の実行基盤、OTel・Capability/Policy・Replay境界の不変継承、Task Faultと`Result.Err`の分離 |
| **境界の完全性** | Integrity by Default（JEP draft 8305968）<br>JEP 451 / 471 / 472 / 486 / 498 / 500 | JDK 21〜26 段階的導入 | `SecurityManager`廃止後のJPMS強カプセル化、`Unsafe`/JNI/`final`フィールド改変・動的agentの既定制限 |
| **AOT・起動性能** | Project Leyden AOT（JEP 483 / 514 / 515 / 516 / 544） | JDK 24〜26 段階導入<br>JEP 544: JDK 28 統合 | OpenJDK標準AOT路線を第一本線とし、動的ロード/プロキシ禁止のClosed-world設計と直結 |
| **ネイティブ・メモリ** | Project Panama FFM API（JEP 454）<br>Vector API（JEP 537） | FFM: JDK 22 Final（JDK 28高速化）<br>Vector: JDK 27 第12 Incubator | JNIを廃した安全なオフヒープ/ネイティブ境界（`Arena`/`Bytes`）、将来のValhalla統合後SIMD基盤 |
| **オブジェクト配置・定数** | Project Lilliput Compact Object Headers（JEP 519 / 534）<br>Lazy Constants（JEP 547） | JEP 534: JDK 27 Default<br>JEP 547: JDK 28 4th Preview | 64bitヘッダによるヒープ削減と22bitクラス上限に沿う特殊化budget、JIT定数畳み込み可能な純粋遅延定数 |
| **観測・秘匿** | JFR Method Timing & Tracing（JEP 520）<br>JFR In-Process Data Redaction（JEP 536） | JEP 520: JDK 25 Final<br>JEP 536: JDK 27 Final | 実行時バイトコード改変不要の計測と、プロセス内JFR秘密情報マスク |

## 2. 構文と基本意味論

v0.3の正規構文は[EBNF.md](EBNF.md)、v0.2からの移行規則は[SYNTAX_MIGRATION.md](SYNTAX_MIGRATION.md)で定義する。文法と文の区切りについてはEBNF.mdを優先する。システム機能の意味論は本書の後続節で定義し、未実装段階の構文は拒否する。

### 2.1 正規構文と名前解決

- ファイルは`package app.name;`で始める。importは一宣言につき一symbolを明示する（`import pkg.Symbol;`）。
- 宣言の可視性はデフォルトでpackage内。`public`を公開宣言に使う。
- 関数はトップレベルに置ける。`fn name(arg: Type) -> Type effects(...)`に統一する。
- 全関数の引数・戻り値・Effect上限は明示する。`val`/`var`の型は局所的に推論できる。
- `val`は再束縛不可、`var`はローカル再束縛可能。代入は文であり値を返さない。
- 文（`val`, `var`, 代入, `return`, 式文）は必ずセミコロン`;`で終える。式ブロック（`if`/`match`/`for-yield`）の末尾結果式には付けない。改行は空白として扱う。
- **関数の戻り値は明示的な`return <expr>;`文を必須とする（`NGJ-RETURN-001`）。** ガード節による早期リターン（`if (cond) { return x; }`）や全分岐での`return`も許可する。`Unit`戻り値の関数では`return`省略、`return;`、`return ();`のいずれも可。一方、`if`/`match`/`for-yield`の式ブロックが値を生成する場合は末尾式で評価する。`Result`の早期失敗伝播は後置`?`または`return Err(...);`で処理する。
- `if`/`while`の条件は括弧、本体は波括弧で囲む。`match`は`match (value) { case Pattern => { ... } }`。
- `if`の両分岐、`match`の全armは期待型に適合させる（`return`で関数から脱出する分岐を除く）。`else`省略時は`Unit`（または早期`return`）を要求する。
- record宣言は`record Name(field: Type);`、生成は`Name(value)`。enum variantも必ず`Variant(...)`形式で定義・構築・分解する。
- 型名は`Int`/`Long`/`Bool`/`String`/`Unit`に統一し、ユーザー定義型も同じ型構文を使う。
- overload、暗黙変換、継承、名前付き/既定/可変長引数、別名import、任意演算子を初期仕様に含めない。
- 式中の明示型引数は`f::<T>(x)`。型の位置は`List<T>`。型情報で構文解析を分岐させない。
- `@derive`は後続段階の登録済み静的展開とし、任意の構文拡張を行わない。

`?name`はprimary式の穴、`expr?`は`Result`の後置伝播。伝播先は最も内側の関数またはlambdaである。普通のblock/if/match/while/build/scopeは伝播先を作らず、forkは子タスクの関数境界を作る。for callback内の伝播・return禁止規則は5.4節に従う。

### 2.2 値型・同一性型・不透明型

NGJの型システムは**value型をデフォルト**とする。同一性（identity）は明示的に必要な場合のみ宣言する。

#### 2.2.1 Value型（デフォルト）

| 宣言 | 意味 | 制約 |
|------|------|------|
| `record` | 不変の積型（v0.3正規構文） | 全フィールドが不変。同一性なし。構造的等価性（v0.2の`data`を統合） |
| `enum` | 閉じた直和型 | 各バリアントもvalue型。パターン分岐を網羅 |
| `opaque record` | 表現を隠す値型（後段） | package外からコンストラクタ不可視 |

Value型の性質:
- **不変:** 生成後にフィールドを変更できない
- **同一性なし:** `System.identityHashCode`、`synchronized`、参照アドレス比較は不可
- **構造的等価性:** `==`はフィールド値による比較。`record Point(x: Int, y: Int);`で`Point(1, 2) == Point(1, 2)`は`true`
- **コピー自由:** 値のコピーは意味論的に区別できない
- **ジェネリクス可能:** `List<Point>`、`Option<Int>`等で使用可能

#### 2.2.2 Identity型（明示宣言・後段機能）

| 宣言 | 意味 | 制約 |
|------|------|------|
| `identity class` | 同一性を持つクラス | 可変フィールド許可。参照比較可能 |
| `resource` | 寿命・所有権付き同一性オブジェクト | affine。暗黙コピー禁止。cleanup必須 |
| `actor` | 隔離された可変状態 | メッセージ経由のみアクセス |

Identity型が必要な場面:
- **可変状態:** フィールドの再代入が必要
- **同期:** `synchronized`ブロックのロック対象
- **参照比較:** 同じオブジェクトであることを識別
- **外部リソース:** ファイルハンドル、DB接続、ネットワークソケット

#### 2.2.3 等価性意味論

| 型の種類 | `==` の意味 | `===` の可否 |
|---------|------------|-------------|
| Value型 | フィールド値による等価性（再帰的） | 使用不可（コンパイルエラー） |
| Identity型 | 参照同一性 | 使用可能（`==`と同義） |

Value型の`==`はフィールドを再帰的に比較する。フィールドがvalue型なら値比較、identity型なら参照比較。

```scala
record Person(name: String, age: Int);
Person("Alice", 30) == Person("Alice", 30);  // true（値等価）

// 後段のIdentity型概念例
identity class Counter { var count: Int }
val c1 = Counter(0);
val c2 = Counter(0);
c1 == c2;   // false（異なるインスタンス）
c1 === c1;  // true（同一インスタンス）
```

`record`内の参照も安全な不変表現でなければならず、identity型フィールドを持つ場合はそのフィールドへの参照が外部で変更されないことを保証する設計が必要。

`Copy`は複製しても寿命・権限・消費制約を破らない型の性質、`Send`は所有権を別タスクへ移せる性質、`Share`は共有借用を並行使用できる性質。コンパイラがフィールドから導出し、アプリ側の無検査な宣言は禁止する。`Copy`は物理的なメモリコピーを必須としない。メソッド呼び出しのreceiverは宣言された`self`モードに従って自動借用できるが、通常の関数引数の借用は`&`で明示する。

不透明型の構築権限とCapabilityを合わせることで、`Verified<T, Source>`を外部入力やキャストから偽造できなくする。出典ラベルは出典の事実を表し、データ内容の真実性を証明しない。

### 2.3 数値型・失敗・不在

#### 2.3.1 数値型はvalue型

NGJの数値型はすべてvalue型である。Javaのprimitive/boxed二重性は**存在しない**。

| NGJ型 | 意味 | 等価性 |
|-------|------|--------|
| `Int` | 符号付き32bit整数 | 値比較 |
| `Long` | 符号付き64bit整数 | 値比較 |
| `Bool` | 真偽値 | 値比較 |
| `Byte` | 符号なし8bit（後段） | 値比較 |
| `Float` | 32bit浮動小数（後段） | 値比較（NaN == NaN は false） |
| `Double` | 64bit浮動小数（後段） | 値比較（NaN == NaN は false） |

```scala
val x: Int = 5;
val y: Int = 5;
x == y;                // true（常に値比較）

// ジェネリクスで自然に使用
val nums: List<Int> = listOf(1, 2, 3);
val opt: Option<Int> = Some(42);
```

**boxing/unboxingの概念は言語に存在しない。** `List<Int>`は`List<Integer>`へのboxingではなく、言語レベルで保証された値型のコレクションである。JVMレベルでの表現（ヒープ割当の有無）は実装詳細であり、言語意味論に影響しない。

**整数演算とオーバーフロー:**
通常の整数演算はオーバーフローを検出し、タスクfaultにする。回復が必要な場合は`checkedAdd`等の`Result` APIで記述する。wrap/saturateは明示APIのみ。

#### 2.3.2 Null廃止とOption

**NGJコア言語に`null`は存在しない。**

- `null`リテラルは提供しない（`NGJ-NULL-001`）
- 参照型の「何もない」状態は`Option<T>`で表現する
- `throw`構文も提供しない（`Result`を使用）

```scala
// 不在の表現
public fn findUser(id: UserId) -> Option<User> effects(DbRead) {
    return ?find_user;
}

// 使用側
match (findUser(id)) {
    case Some(user) => { processUser(user) },
    case None() => { handleNotFound() }
}
```

**`Option<T>`と`Result<T,E>`はvalue型:**
- `Some(x)`と`None()`は値として扱われる
- `Ok(x)`と`Err(e)`も同様
- フラット化・スカラ化の対象（JVM最適化が可能な場合）

#### 2.3.3 Java境界でのnull

Java APIとの境界でのみnullが出現する可能性がある。

| Java側 | NGJ側での扱い |
|--------|--------------|
| `@Nullable T` | `Option<T>` |
| `@NonNull T` | `T`（nullならfault） |
| nullability不明 | `Option<T>`として防御的に扱うか、アダプターで明示指定 |

Java側から受け取ったnullは即座に`None()`へ変換される。NGJ側から返すnullは存在しない（`Option`の`None()`はJava側で適切な表現に変換）。

#### 2.3.4 失敗表現

`Result<T,E>`と`Option<T>`は標準ライブラリのenum型であり、特別なビルトインではない（`Result`に対する`?`演算子のルールを除く）。定義は2.4節を参照。

- `?`は`Result<T, E>`の`Err(e)`を現在の関数へ伝播する。異なるエラー型には明示的な`mapErr`を必要とする
- 失敗する値の黙殺はエラー（`NGJ-TYPE-017`）とし、意図的な破棄には明示操作を要する
- 回復可能なドメインエラーは`Result`、協調キャンセルは`Cancelled`、プログラミング上のfaultはタスク失敗として区別する

### 2.4 Enum — 代数的データ型

NGJの`enum`は**sealed algebraic data type（密封代数的データ型）**であり、ペイロードを持つバリアント（直和型）をサポートする。Rust/Swiftのenum、またはJavaのsealed interface + recordsに相当する。

#### 2.4.1 基本構文

v0.3では、ペイロードの有無にかかわらずすべてのバリアント宣言・構築・パターン照合で丸括弧`(...)`を使用する。

```scala
// ペイロードなしバリアント（古典的enum）
public enum Direction {
    North(),
    South(),
    East(),
    West()
}

// ペイロード付きバリアント（代数的データ型）
public enum Shape {
    Circle(radius: Int),
    Rectangle(width: Int, height: Int),
    Point()  // ペイロードなしも()を付与して混在可能
}

// ジェネリックenum（標準ライブラリ）
public enum Option<T> {
    Some(value: T),
    None()
}

public enum Result<T, E> {
    Ok(value: T),
    Err(error: E)
}
```

#### 2.4.2 Enumの性質

**すべてのenumとそのバリアントはvalue型である:**
- 同一性なし（identity-free）
- 不変
- `==`はバリアント種別とペイロードの値比較
- ジェネリクスで自然に使用可能

```scala
val s1 = Shape.Circle(5);
val s2 = Shape.Circle(5);
s1 == s2;  // true（値等価）

val opt1: Option<Int> = Some(42);
val opt2: Option<Int> = Some(42);
opt1 == opt2;  // true
```

**バリアントは型ではない:**
`Shape.Circle`（または非修飾の`Circle`）は`Shape`型の値を構築するコンストラクタであり、独立した型ではない。`Shape`型の変数のみが存在する。

#### 2.4.3 パターンマッチング

`match`式はenumの網羅的分岐を提供する。**網羅性検査は言語の中核保証**であり、欠落バリアントは正確な診断（`NGJ-MATCH-001`）で報告される。

```scala
public fn area(shape: Shape) -> Int effects() {
    return match (shape) {
        case Circle(radius) => { 3 * radius * radius },
        case Rectangle(width, height) => { width * height },
        case Point() => { 0 }
    };
}
```

**網羅性検査:**
- 全バリアントを網羅しなければコンパイルエラー（`NGJ-MATCH-001`）
- 欠落バリアント名を診断の`missing`および`expected`フィールドに含める（AIフィックスループに重要）
- `_`ワイルドカードまたは変数束縛パターンで残り全部をキャッチ可能

```scala
// エラー例: Non-exhaustive match: missing variants [Point]
public fn brokenArea(shape: Shape) -> Int effects() {
    return match (shape) {
        case Circle(radius) => { 3 * radius * radius },
        case Rectangle(width, height) => { width * height }
        // Point() が欠落 → NGJ-MATCH-001
    };
}
```

**ネストパターンと分解:**

```scala
public enum Expr {
    Literal(value: Int),
    Add(left: Expr, right: Expr),
    Mul(left: Expr, right: Expr)
}

public fn eval(expr: Expr) -> Int effects() {
    return match (expr) {
        case Literal(value) => { value },
        case Add(Literal(a), Literal(b)) => { a + b },
        case Add(left, right) => { eval(left) + eval(right) },
        case Mul(left, right) => { eval(left) * eval(right) }
    };
}
```

> **Note:** パターンガード（`case x if x < 0 => ...`）および選言パターン（`|`）はv0.3コアでは後段機能（deferred）であり、条件分岐には`if`式またはガード節（`if (x < 0) { return "negative"; }`）を使用する。

#### 2.4.4 標準ライブラリのOption/Result

`Option`と`Result`は通常のジェネリックenumであり、特別なビルトイン型ではない。

```scala
// 標準ライブラリでの定義（概念）
package std.core;

public enum Option<T> {
    Some(value: T),
    None()
}

public enum Result<T, E> {
    Ok(value: T),
    Err(error: E)
}
```

**`?`演算子のルール:**

`?`後置演算子は**`Result<T, E>`に対してのみ**早期エラー伝播を行う（`EBNF.md` 5節および`STDLIB.md`と統一）。`Option<T>`に対して`?`を適用すると`NGJ-TYPE-020`エラーとなる。

```scala
// expr? の展開（Result<T, E>の場合）
match (expr) {
    case Ok(value) => { value },
    case Err(error) => { return Err(error); }
}
```

`?`は以下の条件で使用可能:
- 式の型が`Result<T, E>`で、囲む関数の戻り値型が`Result<U, E>`（同じ`E`）
- `Option<T>`の不在処理には`match`、`Option.getOrElse`、または`Option.toResult(opt, err)?`を使用する
- 異なる`E`への変換は明示的な`Result.mapErr`を要求する

#### 2.4.5 ジェネリックEnum

Enumは型パラメータを持てる（ユーザー定義ジェネリック宣言は後段プロファイル、標準ライブラリの`Option<T>` / `Result<T, E>` / `List<T>`はコアで提供）。

```scala
public enum Either<L, R> {
    Left(value: L),
    Right(value: R)
}

public enum Tree<T> {
    Leaf(value: T),
    Node(left: Tree<T>, right: Tree<T>)
}
```

**再帰的enum:**
再帰的なenumは言語でサポートされる。JVM表現では参照を使用するが、言語意味論は値型として扱う。

```scala
public enum List<T> {
    Cons(head: T, tail: List<T>),
    Nil()
}
```

#### 2.4.6 JVM表現

Enumはsealed hierarchy + JEP 401 value classとして出力される（`--target=valhalla`、デフォルト）。Value classは`ACC_IDENTITY`フラグを持たず、フィールド値で比較される。

```scala
// NGJ
public enum Shape {
    Circle(radius: Int),
    Rectangle(width: Int, height: Int),
    Point()
}
```

```java
// JVM出力（JEP 401 value class）
public sealed abstract class Shape permits Shape.Circle, Shape.Rectangle, Shape.Point {
}

public final class Shape.Circle extends Shape {
    private final int radius;
    // structural equals/hashCode generated
}

public final class Shape.Rectangle extends Shape {
    private final int width;
    private final int height;
    // structural equals/hashCode generated
}

public final class Shape.Point extends Shape {
    // structural equals/hashCode generated
}
```

**JVM表現の詳細:**
- 基底型はsealed abstract class（permits句で列挙）
- 各バリアントはfinal class（JEP 401対応時にvalue classへ移行）
- パターンマッチはinstanceofとキャストに展開
- 構造的等価性は`equals`/`hashCode`生成で実現

#### 2.4.7 Java相互運用

**NGJ enumをJavaから使用:**
- Javaからはsealed classとして見える
- pattern matching（Java 21+）で分岐可能
- JEP 401 value class意味論はJDK 28 + preview必須（将来対応）

**Java sealed型をNGJで使用:**
- Java sealed interface + recordsはNGJ enumとして扱える
- アダプターでマッピングを定義
- permits句に含まれないサブタイプがあればエラー

```scala
// Javaのsealed型（概念）
// public sealed interface JShape permits JCircle, JRectangle {}
// public record JCircle(int radius) implements JShape {}
// public record JRectangle(int w, int h) implements JShape {}

// NGJでの使用（アダプター経由・後段）
@java_enum_adapter(JShape)
public enum Shape {
    Circle(radius: Int) = JCircle,
    Rectangle(width: Int, height: Int) = JRectangle
}
```

#### 2.4.8 Record との関係

`record`は単一バリアントのenumの糖衣構文と考えることができる。

```scala
// これは...
public record Point(x: Int, y: Int);

// 概念的にこれと等価
public enum Point {
    Point(x: Int, y: Int)
}
```

実際には`record`は単独のvalue classとして出力され、enumのsealed hierarchy構造は持たない。しかし意味論（value型、不変、構造的等価性）は同一である。

## 3. 所有権・借用・freeze・メモリ

### 3.1 所有権規則

`resource`、builder、タスクハンドル、消費型Capabilityはaffine: move後の元変数の使用を拒否する。分岐の合流点では、全ての到達経路で有効なものだけを使用できる。

- `&T`: 読み取り借用。所有者より長生きできない。
- `&mut T`: 排他的借用。同時に他の借用を持てない。
- 借用中は所有者のmove/close/freezeを拒否する。
- closureへのcaptureにも同じ規則を適用。`fork`はcaptureのSend/Shareと寿命を検査する。
- 所有権機能の初期実装の借用期間は字句スコープ単位。非字句ライフタイムは診断の改善後に導入できる。

### 3.2 buildと不変コレクション

```scala
val values = build (builder: MutableList<Int>) {
    builder.add(1);
    builder.add(2);
    builder
};
```

`builder`はblock内の一意なbuilder。正常終了時に消費し、不変`List<Int>`へ変換する。別名・可変slice・closureを外へ逃がすことは拒否する。Err伝播/cancel/faultではbuilderを破棄し、不完全な値を公開しない。

`var`によるローカル変数の再束縛は許すが、共有オブジェクトの可変性を意味しない。純粋関数内でも外から観測できないローカル構築は許す。

`List`/`Map`/`Set`は不変APIと構造共有を基本とする。独占しているbacking storageを再利用できるbuilderではゼロコピーfreezeを行う。表現変換が必要な場合のコピー・ノード割当は許容し、性能報告へ記録する。永続データ構造全体の無割当や全操作のロックフリー性は保証しない。

コレクションの所有権性質は要素から導出する。`List<Task<T>>`のようにaffine要素を含むListもaffineであり、共有コピー・永続分岐は禁止。消費iteration/mapは元のcontainerから要素をmoveする。要素がShareの場合だけ構造共有を許す。`List.map`はeagerで、左から右に一度ずつcallbackを実行する。

### 3.3 Bytes・Slice・Arena

- `Bytes<'a>`は、寿命`'a`の不変バイト列を参照するview。`Slice<'a,T>`も同様。寿命が推論可能なら省略できるが、所有する値と混同してはならない。
- 不変view自体は寿命制約を保ったままCopyできるが、Send/Shareはbacking storageの属性に従う。
- `OwnedBytes`は所有する不変バイト列。`asBytes()`のviewは所有者を超えて生存できない。
- slice生成は境界を検査し`Result`を返す。範囲は`offset <= len`かつ`length <= len - offset`で検証し、加算のオーバーフローを避ける。
- viewは新しいpayloadコピーを行わないが、viewオブジェクト自体の割当消去はバックエンド依存。
- `Arena`のメモリをviewが参照している間はcloseを拒否する。`scope`と統合したArenaでは子タスクの終了を待ってからcloseする。
- thread-confined Arenaのviewは`Send`ではない。共有Arenaでも、読み取り専用公開と所有者の寿命を検査する。
- mutableなJava/native側の別名を残したまま不変Bytesへ変換してはならない。コピーするか、信頼された独占所有権移譲を必要とする。
- 外へ返す場合は所有者も安全に移譲するか、`OwnedBytes`へ明示コピーする。寿命消去キャストは禁止。

ネイティブメモリおよびネイティブ関数呼び出しの標準境界は **Project Panama FFM API（JEP 454, JDK 22確定）** とし、**NGJ標準コード・新規アダプターでのJNI利用は原則禁止**する。オフヒープ連携・ネイティブ呼び出しはJEP 472（`--enable-native-access`）で許可されたアダプター境界のみに閉じ、FFMの実行時境界検査・寿命検査は防御層として維持する。静的に証明できない箇所を無検査に変換しない。将来のSIMD処理にはValhalla統合後のVector API（JEP 537相当）を候補とする。

### 3.4 resourceとcleanup

`defer`は取得成功後に登録し、逆順で全ての通常終了・早期終了・キャンセル・fault経路で実行する。登録時にcleanup用の借用または所有権を保持し、解放済みの対象への二重登録を拒否する。

cleanupが失敗可能なら、境界APIは`CleanupFailure`を戻り値のエラーに含める。既存の主エラーがある場合はcleanup失敗を付随情報として保持し、主エラーを上書きしない。失敗しても残りのcleanupを試みる。cleanupはキャンセル保留下で実行するが、外部I/Oには期限を必要とする。

DBのcommit/rollbackや支払い解放を、メモリ解放と同じ暗黙destructorに任せない。業務的な補償は失敗・再試行・結果不明を明示するAPIを持つ。

## 4. EffectとCapability

### 4.1 Effect集合

```scala
public fn calculateFee(amount: Money) -> Money effects() { return ?fee; }
public fn findUser(db: &DbRead<Users>, id: UserId)
    -> Result<Option<User>, DbError> effects(DbRead) { return ?find_user; }
```

`effects()`は外部観測可能な業務副作用なし。時刻、乱数、環境変数、ファイル、ネットワーク、DB、process起動、機密解除はそれぞれEffectとして扱う。例: `Clock`, `Random`, `EnvRead`, `FsRead`, `FsWrite`, `Net`, `DbRead`, `DbWrite`, `PaymentWrite`, `Declassify`。

信頼されたアダプターは複数の下位操作を、対象が限定されたドメインEffectにまとめられる（例: PaymentWrite）。manifestには下位操作・Capability・監査要件を残し、無制限な権限を隠さない。通常のアプリコードにはこのEffect再分類を許さない。

式のEffectは実行され得る子式と呼び出し先Effectの和集合。分岐は両側の和集合。関数本体の推論結果は宣言上限の部分集合でなければならない。上位関数もclosureのEffectを保持する。純粋callbackを要求するAPIへ副作用付きclosureを渡せない。

高階関数とEffect多相性は後続段階で定義する。初期の副作用検査は明示された有限集合の和集合・部分集合検査に限定する。callback本体のEffectも呼び出し元へ伝播し、forやforkへの移動で権限検査を迂回できない。

`effects()`は停止性や無faultを意味しない。コンパイラ管理の計測は業務Effectと区別するが、公開/マスク済みデータのみを許し、権限や情報フロー検査を迂回しない。再現テストでは無効化または収集先を固定する。

### 4.2 Capabilityとホストポリシー

Effectは「操作の種類」、Capabilityは「どの対象に何を許すか」を表す。`FsRead<Root>`や`DbWrite<Tenant>`等は信頼されたホストだけが発行する不透明値。文字列、JSON、公開コンストラクターから生成できない。

- Capabilityを持つだけでは操作しない。呼び出し側のEffect上限も満たす必要がある。
- Capabilityの複製・共有可否は発行時の型に含める。単回承認はaffine、読み取り権限は必要に応じShare。
- 委譲は同じか狭い権限のみ。対象・期限・回数・操作の拡大は拒否する。
- パスや接続先が動的なら、実行境界でも検査する。パス正規化、symlink、redirect等を含む対象解決をアダプターの責務とし、単なる文字列prefix比較にしない。
- 実行時の失効・期限切れ・ポリシー拒否は型付きエラー。ソースの注釈だけで許可しない。

エージェントが編集するソースと、信頼されたpolicy/adapter lockを分離する。restricted buildではアダプター追加、`unsafe`の使用許可、Capability発行、機密解除規則の変更をホスト側の検証なしには受理しない。生成コードのコンパイル成功は権限付与を意味しない。

## 5. 並行性・actor・状態遷移

### 5.1 scope/fork/join

`scope { body }`は字句的な子タスク群を所有する。`fork { body }`は原則として仮想スレッドを起動し、`Task<'scope,T>`を返す。ハンドルはscope外へ返せない。`fork`自体は孤児タスクを生成しない。

JVMランタイム上では **Project Loom確定版である `StructuredTaskScope`（JEP 543, JDK 28確定 / JEP 505の`Joiner`合成モデル）** および **`ScopedValue`（JEP 506, JDK 25確定）** を直接のバックエンド基盤とする。`Task<T>.join()`は一度だけ消費し、`Result<T, TaskError>`を返す。子が`Result<U,E>`を返す場合、joinの結果は`Result<Result<U,E>,TaskError>`。専用`Joiner`によりタスク失敗（未捕捉例外 / `ExecutionException`）とドメイン失敗（`Result.Err`）を明確に分離し、暗黙に同一化しない。

| 経路 | 必須動作 |
|---|---|
| 正常なscope終了 | 全子タスクを待機。未処理のタスク失敗を`ScopeError`として返す |
| 子のfault / task failure | fail-fastで兄弟へキャンセル要求し、終了を待つ |
| 子が`Err(E)`という値を返した | 自動キャンセルしない。親が処理し、親のErr終了でキャンセルできる |
| 親のErr/Err伝播/cancel | 未完了の子へキャンセル要求し、join・cleanupしてから退出 |
| deadline超過 | キャンセル要求。強制停止・一定時間内の退出を保証しない |

scope式の正常結果は`Result<T,ScopeError>`。明示joinした失敗は観測済みとして二重報告しない。未観測失敗は生成順で安定して列挙する。scope内の`?`による包含関数の退出でも子タスクの終了待ちを省略しない。`ScopedValue`に束縛されたOTel文脈・Capability/監査ID・Replay境界は`StructuredTaskScope`配下の`fork`へゼロコピーで自動継承される。

キャンセルは協調的であり、Java/nativeの割り込み非対応呼び出しは停止しないことがある。hard timeoutが必要ならプロセス隔離を使用する。仮想スレッドはCPU並列性を無制限に増やさず、JEP 491以降の`synchronized`以外のnative pinningやライブラリ内部ロックも完全には排除しない。

### 5.2 Channelとactor

`Channel<T>`は容量を必須とするbounded channel。満杯ならsendを待機し、キャンセル/closeを型付きエラーで返す。無制限bufferは明示的な別APIとする。`T: Send`を要求し、send成功時に所有権を移譲、失敗時は値を返す。`Share`な不変値の共有はpayloadコピー不要だが、キューの同期コストは存在する。

actorはmailboxから一件ずつ処理し、状態はactor内に閉じる。actor handleはメッセージ送信権限であり、可変状態の共有参照ではない。actorも親scopeに属し、終了・キャンセルを待機する。actorの初期実装ではメッセージ処理中の再入を認めない。相互待機によるdeadlockを型だけで排除するとは主張しない。

### 5.3 Typestateと外部操作

`resource Reservation<State>`等で状態遷移を表す。`commit`と`release`は`Pending`を消費し、二重使用を拒否する。ただしaffine性だけでは「必ずcommit/releaseする」を保証しないため、未解決の業務resourceは`must_resolve`属性を持ち、全正常/エラー退出経路で解決か復旧記録への移譲を要求する。

```text
commit(Pending) -> Committed | Rejected(Pending) | Unknown(RecoveryToken)
release(Pending) -> Released | Unknown(RecoveryToken)
```

タイムアウトは失敗確定ではない。`RecoveryToken`は操作ID、idempotency key、照会に必要な情報を保持し、信頼された復旧ストアへの永続化を必要とする。クラッシュ時は型検査が実行できないため、外部変更前のdurable intentと冪等APIをアダプター側で実装する。

構造化キャンセルは外部副作用のrollbackではない。不正検知の成功前に残高確保を並列実行するなら、補償と結果不明の復旧が必要。サンプルでは検査を先行させ、決済処理は復旧契約を持つアダプターへ集約する。

### 5.4 for / for-yield / while

`for (x <- xs) yield { body }`は各反復の値を収集し、`for (x <- xs) { body }`と`while (condition) { body }`はUnitを返す。Scalaの区別に合わせる。

初期対応は即時評価の標準Listと単一generatorに限定する。入力は一度評価し、左から右にbodyを実行する。for-yieldの結果はList、空入力なら空List。for/whileのbodyはUnit。遅延列、guard、複数generator、任意map/flatMapプロトコルは後続仕様に分離する。

forのbodyはcallback境界であり、body直下の`?`および`return`は拒否する（`NGJ-PROP-002`）。body内のwhileにもこの規則が及ぶ。検証結果を収集するなら`List<Result<T,E>>`を明示的に生成する。複数結果をまとめるtraverse等は標準ライブラリの型付き操作として追加する。独立したlambda内の`?`はそのlambdaだけから退出する。

for callbackとlambdaは初期実装で外側のvarをcaptureできない（`NGJ-CAPTURE-001`）。valのcaptureにも所有権規則を適用する。body内で宣言したvarは使用可能。外側の状態を更新する繰り返しには、その関数内のwhileを使う。break/continue/非局所returnは初期対応に含めない。

## 6. 情報フロー・機密・出典

`Secret<T>`は機密性、`Untrusted<T>`は未検証入力、`Verified<T,S>`は信頼境界を通じて得た出典/検証の証拠を表し、相互に代用しない。

- Secretのmap/連結/シリアライズ結果にもラベルを伝播する。汎用`expose(): T`は提供しない。
- ログ/OTel/JFRにはマスク済みの固定表現か公開値のみを送る。機密を含む例外文・診断・反例・Baggageにも同じ規則を適用する。
- `declassify`にはホスト発行の用途・出力先に束縛されたCapabilityと理由を要求する。操作は監査対象。
- hashや暗号化を一般的な「公開化」とみなさない。出力先に適した変換を信頼されたAPIとして指定する。
- `Untrusted<T>`からの検証は`Result<Validated<T>,ValidationError>`。JSONをparseしただけで業務検証や権限を取得しない。
- 出典の保証を保持できない変換は`Verified`を落とす。AIが任意データに出典ラベルを付けられない。

最初の実装は**明示的なデータフロー**を対象とする。機密を条件にした分岐・例外・実行時間などの暗黙フローと側信道は保証外。完全なnoninterferenceやprompt injection耐性を主張しない。program-counterラベル等は後段の検討項目。

## 7. 契約・反例・検証

後続段階の契約は署名末尾のrequires(condition)、ensures(condition)の順に記述する。`requires`は呼び出し前条件、`ensures`は正常な戻り値への後条件。resource invariantは公開操作の出入口で検査する。契約式は純粋で、検査中の副作用・機密出力を禁止する。検証対象の整数は実装のbit幅とoverflow意味論に合わせる。

```scala
public fn checkedLength(n: Int) -> Result<Long, LengthError> effects()
    ensures (match (result) {
        case Ok(v) => { v >= 0L },
        case Err(_) => { true }
    })
{
    return if (n < 0) { Err(LengthError.Negative()) } else { Ok(n.toLong()) };
}
```

失敗し得る外部入力は原則`Result`で検証し、契約違反を通常の入力エラーに使わない。実行時の契約違反はfaultとして扱う。契約の評価がfaultになる場合も契約失敗であり、無条件成功にはしない。

検証結果は義務ごとに`proved` / `runtime_checked` / `unproven`を返す。証明器のtimeout/unknownは`unproven`。テスト通過を`proved`に昇格しない。proof-required buildでは未証明を拒否し、それ以外は実行時検査を維持する。公開契約の削除・弱化はAPI変更として差分に表示する。

プロパティテストはseed、生成器version、縮小された反例、ツールチェーンIDを記録する。エージェントに渡すのは機密除去後の再現可能なケース。仕様・契約・hidden testsは実装生成対象と分離し、テストを消して成功扱いにしない。

最初の静的検証は純粋・一次の整数/ADT/境界条件に限定。任意再帰の停止性、並行プログラム全体、外部APIの正しさの自動証明は対象外。

## 8. 観測性・監査・再実行

### 8.1 静的計装

`@trace` / `span`は開始、正常終了、ドメインエラー、キャンセル、faultを区別して記録する。Span終了はcleanupに含め、二重終了を禁止する。`event`からJFRイベントを生成する。JFR Method Timing & Tracing（JEP 520, JDK 25確定）と合わせ、計装のための実行時bytecode改変は不要とする。さらにJFR In-Process Data Redaction（JEP 536, JDK 27確定）を前提に、プロセス起動引数・環境変数・システムプロパティ由来の秘密情報もプロセス外へ書き出す前にマスクする。

OTel context、ホストポリシー、Capability/監査相関ID、Replay境界は **ScopedValue（JEP 506, JDK 25確定）** とランタイムadapterで明示的に束縛・継承する。NGJの`scope`/`fork`では`StructuredTaskScope`配下へ自動伝播するが、外部Java executorへはadapterなしに保証しない。Baggageのキー・サイズ・機密性を検査する。O(1)の全体伝播コストは約束しない。

全診断/eventにはbuild ID、source map、symbol IDを関連付けられる。高cardinality属性、payloadサイズ、sampling、exporter backpressureに上限を設ける。通常のtelemetry障害は業務結果を上書きしない。

### 8.2 操作監査

Effect境界では`requested`, `denied`, `succeeded`, `failed`, `unknown`を区別し、operation IDで関連付ける。外部操作の成功はアダプターが確証を得た場合だけ記録する。attemptとcommitを混同しない。

監査必須の操作はsamplingされるOTelとは別のdurable sinkを使う。監査書き込み不能時にfail-closedとするかはホストpolicyで固定し、生成コードが変更できない。トレース欠落を「操作なし」の証拠にしない。

### 8.3 再実行

時刻・乱数・外部I/OをEffect境界および`ScopedValue`束縛のプロバイダ経由で差し替え、入力・応答・seed・依存versionを記録する。replayモードでは実外部副作用を拒否する。fixtureの順序/引数不一致はReplayMismatchとし、黙ってlive呼び出しへfallbackしない。

最初の保証は逐次的な純粋ロジックと記録済みI/O境界の再実行。スレッドスケジュール、native呼び出し、OS状態までの決定的リプレイは含まない。並行実行の順序記録は別段階とする。機密を除去して再現性が失われる場合は、そのケースをreplay不可と明記する。

## 9. Java相互運用と信頼境界

### 9.1 境界署名

Java呼び出しはversion/hash固定のアダプターを経由する。各メソッドにnullability、例外、Effect、Capability、所有権、thread safety、キャンセル、AOT要件を対応付ける。

| Javaの性質 | NGJでの扱い |
|---|---|
| nullableな戻り値 | `Option<T>` |
| 確認済みnon-null戻り値 | `T`。契約違反のnullは境界fault |
| nullability不明 | `Option<T>`またはアダプターの明示検証。無条件nonnullは禁止 |
| 回復可能例外 | `Result<T, AdapterError>`へ明示mapping |
| 割り込み | キャンセルとして伝播。通常の業務失敗へ黙って変換しない |
| VM致命障害 | 成功値や通常Errに偽装しない |
| 可変参照/配列 | resource/借用として扱うか防御的コピー |
| reflection/native/dynamic loading | 信頼された許可と到達可能性情報を要する |

全戻り値の一律Option化はしない。全例外の無損失・ゼロコスト変換も主張しない。未知のEffect/所有権をpureとして推論せず、未検証境界として拒否または隔離する。

### 9.2 Strict AOTとunsafe（Integrity by Default連動）

Class-File APIによる走査は既知のreflection、Unsafe、native、dynamic loading、未解決呼び出しを発見する補助であり、任意の依存JARの安全性証明ではない。推移的依存とruntime profileも検査対象。さらに、ランタイム側の隔離基盤として **OpenJDK Integrity by Default（JEP draft 8305968）** およびJPMSの強カプセル化（Security ManagerはJEP 486により廃止済み）を前提とする。

- NGJの通常コードから実行時reflection、`sun.misc.Unsafe`（JEP 471/498）、JNI（JEP 472）、動的クラスロード、動的エージェント読み込み（JEP 451）は禁止する。
- NGJの`record`/`enum`はJEP 539の`ACC_STRICT_INIT`対象として出力し、deep reflectionによる`final`フィールド改変と標準`ObjectInputStream`迂回をVM検証器水準で拒否する。
- `restricted`プロファイルのJVM実行時は、`--illegal-final-field-mutation=deny`（JEP 500, JDK 26確定、監査時は`jdk.FinalFieldMutation` JFRイベントを収集）、`--enable-native-access`の未付与強制（JEP 472/454）、不要な`--add-opens`禁止をホスト側で強制する。
- 選択したAOT profileで許可されるJava内部reflectionやFFMネイティブ呼び出しは、信頼されたadapterとreachability metadata、smoke test、明示的CLIフラグ許可を必要とする。
- `unsafe java_interop`は監査対象の境界表示であり、許可の発行ではない。restricted sourceでは禁止し、allowlistに固定されたadapter package内だけに限定する。
- v0.1の`aot_verified = true`という自己申告は廃止。検証結果はツールチェーン・依存hash・metadata・試験結果に紐付いたbuild artifactで表す。
- `synchronized`だけを根拠にJava 25向けコードを拒否しない。JDK 24のJEP 491による改善を踏まえ、native pinning、ロック競合、割り込み非対応等を別々に評価する。

プロセス全体の隔離が必要ならOS/container境界も使う。安全でないnative依存を型注釈だけで安全に変更することはできない。

## 10. バックエンド・特殊化・配布

### 10.1 パイプライン

```text
Source -> lossless syntax + holes -> name/type/effect/ownership checks
       -> typed IR + contracts + source map -> backend lowering
       -> .class/.jar (value classes) + ABI manifest + interop/reachability metadata
       -> Valhalla-enabled JVM or AOT build
```

再帰下降・優先度パーサーで式を解析し、Java標準`java.lang.classfile` APIで直接class fileを生成する。Javaソースへのtranspileは必須経路にしない。

型・Effect・所有権・公開契約は共通IRで検査し、バックエンド切替で意味論を変えない。ABI manifestには型/Effect/所有権署名とschema versionを含める。

**Valhalla基盤のプロファイル:**

| target/profile | データ表現 | 前提・制限 |
|---|---|---|
| `valhalla` (基本) | value class + sealed hierarchy (no `ACC_IDENTITY`, fields with `ACC_STRICT`) | JDK 28 EA + `--enable-preview`（JEP 401）をターゲット。record/enumはvalue class（同一性なし）として出力 |
| `valhalla-compat` / `java25` | identity class fallback | Java 25 LTS以上向け。value意味論（構造的等価性・不変性）は維持し、`ScopedValue`(JEP 506)・`Flexible Constructor Bodies`(JEP 513)・Leyden(JEP 514/515)を活用（JVMフラット化なし） |
| `leyden` | valhalla / compat + Leyden AOT cache | **標準AOT本線。** JDK 24〜26（JEP 483/514/515/516）〜JDK 28（JEP 544）。Valhalla結合時はEA検証状況を検査 |
| `native-image` | `valhalla-compat`出力から外部AOT | **外部任意ターゲット**（独立系GraalVM Native Image）。SubstrateVMのvalue class未対応時は`valhalla-compat`経由 |

**AOT互換性の現状（2026年11月時点）:**

| AOTレベル | Valhalla互換 | 状態 |
|-----------|-------------|------|
| JIT (通常JVM) | ✅ 動作 | JDK 28 EA + preview（JEP 401 / JEP 539）で動作確認済み |
| Leyden AOT cache | ⚠️ 組合せ検証中 | JDK 25〜26（JEP 514/515/516）で`valhalla-compat`は運用可能。JDK 28 JEP 544 × value classの結合を要検証 |
| Native Image (外部) | ❌ 未サポート | Oracle製品版は2025年9月にJava SEから分離。独立系SubstrateVMはvalue class未対応のため`valhalla-compat`で生成 |

**AOTレベル劣化とエラー:**
- `--aot=leyden`指定時にLeyden/Valhalla組み合わせが未サポートなら、明示エラーまたは`valhalla-compat`/JITへのfallbackを警告付きで提示
- `--aot=native`指定時にvalue classサポートがなければ、`valhalla-compat`出力からのnative-imageビルドを試行するか、明示エラー
- 意味論は全AOTレベルで同一を維持。性能特性のみが異なる

Loom（`ScopedValue` JEP 506 / `StructuredTaskScope` JEP 543）・Valhalla（JEP 401/539）・Lazy Constants（JEP 547）の利用可否とflagsはprofileに記録し、使用JDKでのAPI差分はruntime adapterに閉じる。Valhallaのflagsや配置規則を将来の全JDKに固定せず、JDK更新時に再検証する。また、静的コード変換や契約・計算グラフ表現（GPU/SQL等）を`.class`に保持する将来拡張として、OpenJDK Project Babylon（Code Reflection）をpost-v0.3研究対象とする。

Project Leyden AOTを本線としnative-imageを任意ターゲットとして扱うAOTレベル設計については[AOT設計方向](AOT.md)を参照。

### 10.2 ジェネリクスと特殊化

#### 10.2.1 ジェネリクスはvalue型を含む（言語保証）

NGJのジェネリクスはvalue型を第一級でサポートする。これは**言語レベルの保証**であり、JVM実装の最適化状況に依存しない。

```scala
// すべて有効なNGJコード
val ints: List<Int> = listOf(1, 2, 3);
val points: List<Point> = listOf(Point(0, 0), Point(1, 1));
val results: List<Result<Int, Error>> = listOf(Ok(1));
val nested: Option<Option<Int>> = Some(Some(42));
```

**Javaとの違い:**
- Javaではprimitive型はジェネリクスに使用不可（`List<int>`はエラー）
- NGJでは`Int`はvalue型であり、`List<Int>`は自然に記述可能
- boxing/unboxingの概念は言語に存在しない

#### 10.2.2 性能は実装依存（最適化機会）

言語意味論とJVM性能最適化を分離する。

| 機能 | 言語意味論 | JVM最適化（2026年11月時点） |
|------|-----------|---------------------------|
| `record Point(x: Int, y: Int);` | value型、構造的等価性 | ✅ フラット化対象（64bit以下） |
| `Option<Point>` | value型enum | ⚠️ サイズ・ネスト深度による（ヒープ割当時もJEP 534 Compact Object Headersで8Bヘッダ） |
| `List<Int>` | Int値のコレクション | ⚠️ ヒープ割当（Parametric JVM待ち、JEP 534でヘッダ負荷軽減） |
| `List<Point>` | Point値のコレクション | ⚠️ ヒープ割当（Parametric JVM待ち、JEP 534でヘッダ負荷軽減） |

**実装状況（リスク節に詳細）:**
- Project LilliputのCompact Object Headers（JEP 519 JDK 25 / **JEP 534 JDK 27 デフォルト**）により、ヒープ確保される`enum`バリアントや`valhalla-compat`のヘッダ負荷は低減される
- 純粋なパッケージ定数・遅延計算キャッシュにはLazy Constants（JEP 547）によるJIT定数畳み込みの活用を想定する
- Parametric JVM（ジェネリック特殊化）は研究段階
- JITスカラ化はwarmup後のみ有効、64bit超のvalue classフラット化は限定的

#### 10.2.3 特殊化ヒューリスティック

将来のParametric JVM出荷時に備えた設計:
- 参照型ジェネリクスの共有と、値型特殊化を想定
- 特殊化数・コードサイズにbudgetを設け、超過時は共有表現fallback（JEP 534 Compact Object Headers の22bitクラスポインタによる約400万クラス上限への配慮とも整合）
- 公開ABIは特殊化された内部名を露出しない

`--emit-layout-report`は配置・特殊化・割当情報を出力する候補機能。

**性能無条件保証の禁止:**
「全割当がゼロ」「全ジェネリクスがフラット化」等の無条件な性能保証は行わない。言語は意味論を保証し、性能は実測で評価する。

## 11. 実装段階と未決事項

詳細な完了条件は[VALIDATION.md](VALIDATION.md)を参照する。

**Valhallaは基盤であり、Phase 4ではない。** 以下のフェーズはValhalla意味論を前提とする。

1. **Phase 1:** `fn`/`record`/`enum`（代数的データ型）/`Result`/`Option`、`package`/`import`、型検査、関数`return`必須検査、**Valhalla value意味論**、Typed Holes、構造化診断、型文脈検索、**`match`網羅性検査**。Effect宣言を保存し、未実装の強制機能を黙って受理しない。
2. **Phase 2:** Effect/Capability、所有権・freeze、Bytes/Arena、Java adapterの信頼境界、restricted build。小さい意味論モデルとnegative testsを先に用意する。
3. **Phase 3:** scope/fork/Channel/actor、Typestate、契約・property tests、明示的情報フロー、OTel/JFR、逐次I/O replay。
4. **Phase 4:** AOTレベル統合（Leyden/native-image互換性行列）、ジェネリック特殊化最適化、限定的証明器、AI生成の比較実験。

AOTレベル（JIT/Leyden/native-image）の統一的なUXとProject Leyden対応については[AOT設計方向](AOT.md)を参照。Phase 4以降の実装計画として位置づける。

未決事項: 効果多相性の一般化範囲、actor supervisionの拡張、非字句借用、任意handler、暗黙情報フロー、並行replay、証明器の具体選定。未決機能を利用するソースは`unsupported_feature`として拒否し、実装済み保証と混在させない。

詳細は[MVP_COVERAGE.md](MVP_COVERAGE.md)を参照。

## 12. オープンクエスチョンとリスク

### 12.1 JDK/Valhalla関連リスク

| リスク | 影響 | 緩和策 |
|--------|------|--------|
| JDK 28 preview変更 | ソース非互換の可能性 | マイナーバージョンで追従。意味論は維持 |
| JEP 401 / JEP 539がfinalにならない | value class / strict init出力が使用不可 | valhalla-compat fallback（identity classで意味論維持、`--illegal-final-field-mutation=deny`併用） |
| Null-Restricted Types (JEP 8316779) 遅延 | Java境界でのnull扱いに制約 | Option変換をコンパイラ側で生成 |
| Parametric JVM 長期遅延 | ジェネリック性能最適化なし | 言語意味論は影響なし。JEP 534 Compact Headers等でヒープ負荷を軽減 |

### 12.2 ツールチェーン/CI関連リスク

| リスク | 影響 | 緩和策 |
|--------|------|--------|
| Gradle/Maven JDK 28 EA対応遅延 | ビルド困難 | toolchain指定、直接javac呼び出し |
| Project Leyden + Valhalla組み合わせ未検証 | JDK 28 `leyden`でのvalue classキャッシュ使用不可 | JDK 25/26 `valhalla-compat` LeydenまたはJIT fallback |
| 外部GraalVM Native ImageのValhalla未対応・分離リスク | 独立系GraalVMでの`native`ビルド制約 | OpenJDK本線のLeydenを優先し、必要時のみ`valhalla-compat`出力から生成 |
| Integrity by Default制限（JEP 472 / 500）による既存Java依存失敗 | 推移的依存での警告/例外 | アダプター境界での隔離と依存lock・JFR監査（`jdk.FinalFieldMutation`） |
| IDE/LSP JDK 28 preview対応 | 開発体験低下 | CLI中心のワークフロー維持 |

### 12.3 設計上の未決事項

| 項目 | 状態 | 方針 |
|------|------|------|
| identity型の詳細構文 | 未確定 | `identity class`暫定。フィードバック後確定 |
| Java sealed型の自動マッピング | 未確定 | アダプター経由を基本。自動推論は後続 |
| 再帰的enumの最大深度 | 未確定 | スタックオーバーフロー回避策を検討 |
| パターンマッチのor-pattern | 未確定 | `Circle(r) \| Point() => ...`の構文検討中 |

### 12.4 移行パス

JEP 401 value class出力の段階的完成:

1. **完了:** v0.3構文正規化、関数`return`必須化、Value型意味論・網羅性検査の実装
2. **完了:** JDK 28 EA + `--enable-preview`対応、JEP 401 value class（no `ACC_IDENTITY`）+ `ACC_STRICT` fields preview classfile直接出力、Option/Result ADT sealed hierarchy
3. **JDK 28 GA後（2027年3月予定）:** 本番利用開始、Project Leyden（JEP 544）との結合検証、`StructuredTaskScope`（JEP 543）連携の有効化
4. **preview卒業後:** 安定版としてリリース

## 13. v0.2からの変更（v0.3）

### 13.1 Valhalla基盤への移行

- **Valhallaを基盤に:** オプションのバックエンドから言語設計の根幹へ。Phase 4から全フェーズの前提へ移動
- **Value型デフォルト:** `record`/`enum`はすべてvalue型（identity-free）。identity型は明示宣言のみ
- **Primitive統一:** `Int`/`Long`/`Bool`等はvalue型。boxed/primitive二重性を廃止
- **Null廃止:** コア言語から`null`を排除。不在は`Option<T>`のみ

### 13.2 Enum代数的データ型と構文統一

- **Enumをsealed ADTに:** ペイロード付きバリアント`Variant(field: Type)`（直和型）をサポート
- **Result/Optionを通常enum化:** 特別なビルトインから標準ライブラリのenumへ（`Result`に対する`?`ルールは維持）
- **パターンマッチング強化:** ネスト・分解パターン、網羅性検査（`NGJ-MATCH-001`）
- **関数の`return`必須化:** 非`Unit`関数は明示的な`return <expr>;`を必須化（`NGJ-RETURN-001`）。ガード節による早期リターンもサポートし、`if`/`match`/`for-yield`の式ブロックは末尾式評価を維持

### 13.3 ジェネリクスとJVM表現

- **ジェネリクスはvalue型を含む:** `List<Int>`は言語保証。性能最適化は実装依存
- **JVM表現明確化:** enumはsealed hierarchy of value classes
- **AOT互換性:** Leyden/native-imageとの互換性状況を明示

## 14. v0.3の構文正規化と複雑性削減

構文は[EBNF.md](EBNF.md)に一元化し、旧表記（`module`, `pub`, `data`, `let`, `! { ... }`）を別名として受理せず移行診断（`NGJ-MIGRATE-*`）を出す。関数の戻り値は明示的な`return <expr>;`に統一し、回復可能な失敗伝播は`Result`と`?`に統一する。全関数の境界型・Effectを明示し、推論は局所的に限定する。Scala方式のループ（`for`/`for-yield`/`while`）を標準`List`から導入し、callbackからの非局所退出（`?`および`return`）を禁止する。所有権・並行性・情報フローの保証は各段階の検査が完成してから有効化する。

移行規則と適合性テストは[SYNTAX_MIGRATION.md](SYNTAX_MIGRATION.md)で管理する。

## 15. v0.1からの変更（v0.2）

- AIによる生成・修正を言語設計の目的に追加し、検査器の外部APIを仕様化。
- `Result`に統一し、union代替・一律Option化・自己申告AOT検証を削除。
- 値型=無割当、全I/O=非blocking、チャネル=無lock等の無条件な性能保証を撤回し、測定対象へ変更。
- freeze、Arena、join、キャンセル、cleanup、外部操作の結果不明を詳細化。
- Effect/Capability、Typestate、情報フロー、契約、再実行、信頼境界を追加。
- サンプルは安全なアダプター境界を使う設計例へ更新。コンパイル済みと誤認させない。
