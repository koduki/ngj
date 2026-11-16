# NGJ — Concept & Language Specification

> Draft v0.3 / 2026-11-17
> Status: language design, not an implemented compiler or a production security guarantee.
> Baseline: Valhalla対応JDK（JDK 28 EA + `--enable-preview`）。Valhallaはオプションのバックエンドではなく、言語設計の基盤である。

## 1. 目的・適用範囲

NGJは、AIエージェントが生成・修正することを第一級の利用形態とする、静的型付け・式指向のJVM言語である。人間がレビューできるソースコードを保ち、コンパイラが型・所有権・副作用・契約の情報を生成側に返す。Javaのライブラリ資産を境界アダプター経由で利用し、サーバー、CLI、バイナリ処理を主な用途とする。

### 1.1 設計原則

1. **Value first (Valhalla foundation):** 不変の値と、同一性・状態・寿命を持つリソースを分離する。Project Valhallaのvalue classesを言語設計の基盤とし、プリミティブ型とユーザー定義値型を統一する。NGJにおける`Int`/`Long`/`Bool`等はvalue型であり、boxed/primitive の二重性をユーザーに見せない。VMによるフラット化・スカラ化は性能最適化であり、言語の意味論は値の等価性と不変性に基づく。
2. **Colorless structured concurrency:** `scope` / `fork` と仮想スレッドによって、`async/await`なしの直接的な記述を提供する。
3. **Checkable authority:** 型・Effect・Capabilityで、実行可能な操作と対象を表す。安全性をソース中の自己申告に依存させない。
4. **Compiler as an agent interface:** 型付き穴、部分プログラム検査、構造化診断、意味に基づく文脈取得をコンパイラの公開機能とする。
5. **Observable and verifiable behavior:** OTel/JFR、契約、反例、I/O再実行を接続する。観測と保証を区別する。
6. **Static interop and AOT:** NGJ自身は実行時リフレクションに依存せず、Java境界とバックエンドの要件をビルド時に検査する。

AIを言語ランタイムやコンパイラの必須依存にはしない。同じソース・依存・ツールチェーンからのビルドにLLM呼び出しは不要。エージェント専用構文やプロンプトDSL、任意の依存型、完全な自動証明はv0.3の必須要件ではない。

### 1.3 Valhalla基盤 — 型システムの根幹

NGJはProject Valhallaのvalue classesを言語設計の**基盤**とする。これはオプションの最適化バックエンドではなく、型システムと標準ライブラリの根幹である。

**設計原則:**
- **Value型がデフォルト:** すべてのユーザー定義型はvalue型として設計する。同一性（identity）は明示的に必要な場合のみ宣言する
- **Primitive/Boxed分離の廃止:** `Int`/`Long`/`Bool`等はvalue型であり、ユーザーにprimitive/boxedの二重性を見せない
- **nullの廃止:** コア言語に`null`リテラルは存在しない。不在は`Option<T>`で表現する
- **値等価性がデフォルト:** `==`はすべてのvalue型でフィールド値による等価性比較
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
| Null廃止 / Option | 必須 | JEP draft 8316779 Null-Restricted Types | Draft |
| Primitive統一 | 必須 | JEP 402 Enhanced Primitive Boxing | Draft |
| ジェネリック特殊化 | 意味論保証、性能は実装依存 | Parametric JVM | 研究段階 |

**JDKピン方針:**
- 開発時は JDK 28 EA + `--enable-preview` を必須とする
- 仕様はJDKの現状に妥協せず理想を記述し、実装がそれを追う
- JVM機能の不足部分はコンパイラ側で補完（ランタイムチェック、ラッパー生成等）

**リスクと移行:**
詳細は本文書末尾の「オープンクエスチョンとリスク」節を参照。

### 1.2 文書の読み方と保証範囲

本文の「必須」「拒否」は将来実装が満たすべき規範要件。「候補」「後段」は未実装の研究・拡張項目。すべての構文例は提案構文であり、実行確認済みコードではない。

- 本文: 言語・ランタイム・Java境界の規範仕様。
- [AGENT_TOOLING.md](AGENT_TOOLING.md): CLI/診断/部分検査のプロトコル。
- [VALIDATION.md](VALIDATION.md): 適合性試験、性能検証、AI生成評価、実装順。
- [RESEARCH.md](RESEARCH.md): 研究との対応、出典と証拠の限界。
- [examples/README.md](../examples/README.md): サンプルの前提と未定義アプリAPI。

型検査は業務仕様の正しさ、停止性、外部サービスの応答、タイミング側信道を一般には保証しない。安全性は、検査器・標準ランタイム・許可されたアダプター・ホストのポリシーを信頼基盤とする。任意のJava/JNIコードを同一プロセスにロードした場合に、型だけで隔離できるとは主張しない。

## 2. 構文と基本意味論

### 2.1 モジュール・名前解決

- ファイルは`module app.name`で始める。importは明示し、ワイルドカードimportは提供しない。
- 宣言の可視性はデフォルトでmodule内。`pub`だけを外部へ公開する。
- 型名・値名は解決後のシンボルIDで識別する。曖昧なimportはエラーとし、暗黙の候補選択をしない。
- 公開関数は引数型、戻り値型、Effect上限を明記する。ローカル変数・非公開関数のEffectは推論できる。再帰する公開APIは明示署名を要する。
- 暗黙の数値変換、暗黙のnull許容、ユーザー定義の暗黙変換はない。名前付き引数は宣言された引数名と一致させる。
- `@derive(JsonCodec, CliArgs)`等は登録済みの決定的なコンパイル時展開。生成コードも同じ検査を通す。任意のビルドスクリプト実行権限を与えない。

概略構文（完全な字句/EBNF仕様はPhase 1で固定）:

```text
function := [pub] fn name [<type-parameters>] (parameters)
            -> type ! {effect-list} [contract-clauses] block
hole     := ?identifier
propagate := expression ?
borrow   := &expression | &mut expression
```

`?name`は式の開始位置の穴、`expr?`は後置のエラー伝播として区別する。ブロック末尾の式が値。引数・演算子の評価は左から右、`&&` / `||`は短絡評価。`match`は網羅性を検査する。`return`と`?`は包含関数を終了し、その途中のcleanupを実行する。

### 2.2 値型・同一性型・不透明型

NGJの型システムは**value型をデフォルト**とする。同一性（identity）は明示的に必要な場合のみ宣言する。

#### 2.2.1 Value型（デフォルト）

| 宣言 | 意味 | 制約 |
|------|------|------|
| `data` | 不変の積型 | 全フィールドが不変。同一性なし。構造的等価性 |
| `record` | dataの簡潔形式 | フィールド名から自動導出。dataと同等 |
| `enum` | 閉じた直和型 | 各バリアントもvalue型。パターン分岐を網羅 |
| `opaque data` | 表現を隠す値型 | module外からコンストラクタ不可視 |

Value型の性質:
- **不変:** 生成後にフィールドを変更できない
- **同一性なし:** `System.identityHashCode`、`synchronized`、参照アドレス比較は不可
- **構造的等価性:** `==`はフィールド値による比較。`data Point { x: Int, y: Int }`で`Point(1,2) == Point(1,2)`は`true`
- **コピー自由:** 値のコピーは意味論的に区別できない
- **ジェネリクス可能:** `List<Point>`、`Option<Int>`等で使用可能

#### 2.2.2 Identity型（明示宣言）

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

```
data Person { name: String, age: Int }
Person("Alice", 30) == Person("Alice", 30)  // true（値等価）

identity class Counter { var count: Int }
let c1 = Counter(0)
let c2 = Counter(0)
c1 == c2   // false（異なるインスタンス）
c1 === c1  // true（同一インスタンス）
```

`data`内の参照も安全な不変表現でなければならず、identity型フィールドを持つ場合はそのフィールドへの参照が外部で変更されないことを保証する設計が必要。

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
| `Byte` | 符号なし8bit | 値比較 |
| `Float` | 32bit浮動小数 | 値比較（NaN == NaN は false） |
| `Double` | 64bit浮動小数 | 値比較（NaN == NaN は false） |

```
Int(5) == Int(5)      // true
let x: Int = 5
let y: Int = 5
x == y                // true（常に値比較）

// ジェネリクスで自然に使用
let nums: List<Int> = List.of(1, 2, 3)
let opt: Option<Int> = Some(42)
```

**boxing/unboxingの概念は言語に存在しない。** `List<Int>`は`List<Integer>`へのboxingではなく、言語レベルで保証された値型のコレクションである。JVMレベルでの表現（ヒープ割当の有無）は実装詳細であり、言語意味論に影響しない。

**整数演算とオーバーフロー:**
通常の整数演算はオーバーフローを検出し、タスクfaultにする。回復が必要な場合は`checkedAdd`等の`Result` APIで記述する。wrap/saturateは明示APIのみ。

#### 2.3.2 Null廃止とOption

**NGJコア言語に`null`は存在しない。**

- `null`リテラルは提供しない
- 参照型の「何もない」状態は`Option<T>`で表現する
- `throw`構文も提供しない（`Result`を使用）

```
// 不在の表現
fn findUser(id: UserId) -> Option<User> ! { DbRead }

// 使用側
match findUser(id) {
    Some(user) => processUser(user),
    None => handleNotFound()
}
```

**`Option<T>`と`Result<T,E>`はvalue型:**
- `Some(x)`と`None`は値として扱われる
- `Ok(x)`と`Err(e)`も同様
- フラット化・スカラ化の対象（JVM最適化が可能な場合）

#### 2.3.3 Java境界でのnull

Java APIとの境界でのみnullが出現する可能性がある。

| Java側 | NGJ側での扱い |
|--------|--------------|
| `@Nullable T` | `Option<T>` |
| `@NonNull T` | `T`（nullならfault） |
| nullability不明 | `Option<T>`として防御的に扱うか、アダプターで明示指定 |

Java側から受け取ったnullは即座に`None`へ変換される。NGJ側から返すnullは存在しない（`Option`の`None`はJava側で適切な表現に変換）。

#### 2.3.4 失敗表現

`Result<T,E>`と`Option<T>`は標準ライブラリのenum型であり、特別なビルトインではない（`?`演算子のルールを除く）。定義は2.4節を参照。

- `?`は`Err(e)`を現在の関数へ伝播する。異なるエラー型には明示的な`mapErr`を必要とする
- 失敗する値の黙殺はエラーとし、意図的な破棄には理由付きの明示操作を要する
- 回復可能なドメインエラーは`Result`、協調キャンセルは`Cancelled`、プログラミング上のfaultはタスク失敗として区別する

### 2.4 Enum — 代数的データ型

NGJの`enum`は**sealed algebraic data type（密封代数的データ型）**であり、ペイロードを持つバリアント（直和型）をサポートする。Rust/Swiftのenum、またはJavaのsealed interface + recordsに相当する。

#### 2.4.1 基本構文

```
// ペイロードなしバリアント（古典的enum）
enum Direction {
    North,
    South,
    East,
    West
}

// ペイロード付きバリアント（代数的データ型）
enum Shape {
    Circle { radius: Float },
    Rectangle { width: Float, height: Float },
    Point  // ペイロードなしも混在可能
}

// ジェネリックenum
enum Option<T> {
    Some { value: T },
    None
}

enum Result<T, E> {
    Ok { value: T },
    Err { error: E }
}
```

#### 2.4.2 Enumの性質

**すべてのenumとそのバリアントはvalue型である:**
- 同一性なし（identity-free）
- 不変
- `==`はバリアント種別とペイロードの値比較
- ジェネリクスで自然に使用可能

```
let s1 = Shape.Circle { radius: 5.0 }
let s2 = Shape.Circle { radius: 5.0 }
s1 == s2  // true（値等価）

let opt1: Option<Int> = Some { value: 42 }
let opt2: Option<Int> = Some { value: 42 }
opt1 == opt2  // true
```

**バリアントは型ではない:**
`Shape.Circle`は`Shape`型の値を構築する構文であり、独立した型ではない。`Shape`型の変数のみが存在する。

#### 2.4.3 パターンマッチング

`match`式はenumの網羅的分岐を提供する。**網羅性検査は言語の中核保証**であり、欠落バリアントは正確な診断で報告される。

```
fn area(shape: Shape) -> Float ! {} {
    match shape {
        Circle { radius } => 3.14159 * radius * radius,
        Rectangle { width, height } => width * height,
        Point => 0.0
    }
}
```

**網羅性検査:**
- 全バリアントを網羅しなければコンパイルエラー
- 欠落バリアント名を診断に含める（AIフィックスループに重要）
- `_`ワイルドカードで残り全部をキャッチ可能（ただし将来のバリアント追加で網羅漏れリスク）

```
// エラー例: 'Point' variant not covered in match expression
fn broken_area(shape: Shape) -> Float ! {} {
    match shape {
        Circle { radius } => 3.14159 * radius * radius,
        Rectangle { width, height } => width * height
        // Point が欠落 → コンパイルエラー
    }
}
```

**ネストパターンと分解:**

```
enum Expr {
    Literal { value: Int },
    Add { left: Expr, right: Expr },
    Mul { left: Expr, right: Expr }
}

fn eval(expr: Expr) -> Int ! {} {
    match expr {
        Literal { value } => value,
        Add { left: Literal { value: a }, right: Literal { value: b } } => a + b,
        Add { left, right } => eval(left) + eval(right),
        Mul { left, right } => eval(left) * eval(right)
    }
}
```

**ガード条件:**

```
fn describe(n: Int) -> String ! {} {
    match n {
        x if x < 0 => "negative",
        0 => "zero",
        x if x < 10 => "small positive",
        _ => "large positive"
    }
}
```

#### 2.4.4 標準ライブラリのOption/Result

`Option`と`Result`は通常のジェネリックenumであり、特別なビルトイン型ではない。

```
// 標準ライブラリでの定義（概念）
package std

public enum Option<T> {
    Some { value: T },
    None
}

public enum Result<T, E> {
    Ok { value: T },
    Err { error: E }
}
```

**`?`演算子のルール:**

`?`演算子は`Result`と`Option`に対して特別な伝播動作を持つ。これは構文糖であり、型の特別扱いではない。

```
// expr? の展開（Resultの場合）
match expr {
    Ok { value } => value,
    Err { error } => return Err { error }
}

// expr? の展開（Optionの場合）
match expr {
    Some { value } => value,
    None => return None
}
```

`?`は以下の条件で使用可能:
- 式の型が`Result<T, E>`で、囲む関数の戻り値型が`Result<U, E>`（同じE）
- 式の型が`Option<T>`で、囲む関数の戻り値型が`Option<U>`
- 異なる`E`への変換は明示的な`mapErr`を要求

#### 2.4.5 ジェネリックEnum

Enumは型パラメータを持てる。

```
enum Either<L, R> {
    Left { value: L },
    Right { value: R }
}

enum Tree<T> {
    Leaf { value: T },
    Node { left: Tree<T>, right: Tree<T> }
}
```

**再帰的enum:**
再帰的なenumは言語でサポートされる。JVM表現では参照を使用するが、言語意味論は値型として扱う。

```
enum List<T> {
    Cons { head: T, tail: List<T> },
    Nil
}
```

#### 2.4.6 JVM表現

Enumはsealed hierarchyのvalue classesとして出力される。

```
// NGJ
enum Shape {
    Circle { radius: Float },
    Rectangle { width: Float, height: Float },
    Point
}

// JVM出力（概念）
public sealed value interface Shape permits Shape.Circle, Shape.Rectangle, Shape.Point {
}

public final value class Shape.Circle implements Shape {
    private final float radius;
    // ...
}

public final value class Shape.Rectangle implements Shape {
    private final float width;
    private final float height;
    // ...
}

public final value class Shape.Point implements Shape {
    // singleton-like
}
```

**JVM表現の詳細:**
- 基底型はsealed value interface
- 各バリアントはvalue class（permits句で列挙）
- ペイロードなしバリアントは最適化でシングルトン化可能
- パターンマッチはinstanceofとキャストに展開

#### 2.4.7 Java相互運用

**NGJ enumをJavaから使用:**
- Javaからはsealed interfaceとして見える
- pattern matching（Java 21+）で分岐可能
- value class意味論はJDK 28 + preview必須

**Java sealed型をNGJで使用:**
- Java sealed interface + recordsはNGJ enumとして扱える
- アダプターでマッピングを定義
- permits句に含まれないサブタイプがあればエラー

```
// Javaのsealed型
public sealed interface JShape permits JCircle, JRectangle {}
public record JCircle(double radius) implements JShape {}
public record JRectangle(double w, double h) implements JShape {}

// NGJでの使用（アダプター経由）
@java_enum_adapter(JShape)
enum Shape {
    Circle { radius: Float } = JCircle,
    Rectangle { width: Float, height: Float } = JRectangle
}
```

#### 2.4.8 Data/Record との関係

`data`と`record`は単一バリアントのenumの糖衣構文と考えることができる。

```
// これは...
data Point { x: Int, y: Int }

// 概念的にこれと等価
enum Point {
    Point { x: Int, y: Int }
}
// ただしPointは直接構築可能: Point { x: 1, y: 2 }
```

実際には`data`/`record`は単独のvalue classとして出力され、enumのsealed hierarchy構造は持たない。しかし意味論（value型、不変、構造的等価性）は同一である。

## 3. 所有権・借用・freeze・メモリ

### 3.1 所有権規則

`resource`、builder、タスクハンドル、消費型Capabilityはaffine: move後の元変数の使用を拒否する。分岐の合流点では、全ての到達経路で有効なものだけを使用できる。

- `&T`: 読み取り借用。所有者より長生きできない。
- `&mut T`: 排他的借用。同時に他の借用を持てない。
- 借用中は所有者のmove/close/freezeを拒否する。
- closureへのcaptureにも同じ規則を適用。`fork`はcaptureのSend/Shareと寿命を検査する。
- MVPの借用期間は字句スコープ単位。非字句ライフタイムは診断の改善後に導入できる。

### 3.2 buildと不変コレクション

```rust
let values = build MutableList<Int> {
    this.add(1)
    this.add(2)
    this
}
```

`this`はblock内の一意なbuilder。正常終了時に消費し、不変`List<Int>`へ変換する。別名・可変slice・closureを外へ逃がすことは拒否する。早期return/Err/cancel/faultではbuilderを破棄し、不完全な値を公開しない。

`let mut`によるローカル変数の再束縛は許すが、共有オブジェクトの可変性を意味しない。純粋関数内でも外から観測できないローカル構築は許す。

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

FFMの実行時境界検査・寿命検査は防御層として維持する。静的に証明できない箇所を無検査に変換しない。

### 3.4 resourceとcleanup

`defer`は取得成功後に登録し、逆順で全ての通常終了・早期終了・キャンセル・fault経路で実行する。登録時にcleanup用の借用または所有権を保持し、解放済みの対象への二重登録を拒否する。

cleanupが失敗可能なら、境界APIは`CleanupFailure`を戻り値のエラーに含める。既存の主エラーがある場合はcleanup失敗を付随情報として保持し、主エラーを上書きしない。失敗しても残りのcleanupを試みる。cleanupはキャンセル保留下で実行するが、外部I/Oには期限を必要とする。

DBのcommit/rollbackや支払い解放を、メモリ解放と同じ暗黙destructorに任せない。業務的な補償は失敗・再試行・結果不明を明示するAPIを持つ。

## 4. EffectとCapability

### 4.1 Effect集合

```rust
pub fn calculateFee(amount: Money) -> Money ! {} { ... }
pub fn findUser(db: &DbRead<Users>, id: UserId)
    -> Result<Option<User>, DbError> ! { DbRead } { ... }
```

`! {}`は外部観測可能な業務副作用なし。時刻、乱数、環境変数、ファイル、ネットワーク、DB、process起動、機密解除はそれぞれEffectとして扱う。例: `Clock`, `Random`, `EnvRead`, `FsRead`, `FsWrite`, `Net`, `DbRead`, `DbWrite`, `PaymentWrite`, `Declassify`。

信頼されたアダプターは複数の下位操作を、対象が限定されたドメインEffectにまとめられる（例: PaymentWrite）。manifestには下位操作・Capability・監査要件を残し、無制限な権限を隠さない。通常のアプリコードにはこのEffect再分類を許さない。

式のEffectは実行され得る子式と呼び出し先Effectの和集合。分岐は両側の和集合。関数本体の推論結果は宣言上限の部分集合でなければならない。上位関数もclosureのEffectを保持する。純粋callbackを要求するAPIへ副作用付きclosureを渡せない。

再利用可能な高階関数にはEffect変数`ε`を用いた有限集合上の多相性を設ける。MVPでは明示的な単一Effect変数と和集合に制限し、handlerによる任意Effect消去は導入しない。`fork`内のEffectも親の推論結果に含め、並列化で権限検査を迂回できない。

`! {}`は停止性や無faultを意味しない。コンパイラ管理の計測は業務Effectと区別するが、公開/マスク済みデータのみを許し、権限や情報フロー検査を迂回しない。再現テストでは無効化または収集先を固定する。

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

`Task<T>.join()`は一度だけ消費し、`Result<T, TaskError>`を返す。子が`Result<U,E>`を返す場合、joinの結果は`Result<Result<U,E>,TaskError>`。タスク失敗とドメイン失敗を暗黙に同一化しない。

| 経路 | 必須動作 |
|---|---|
| 正常なscope終了 | 全子タスクを待機。未処理のタスク失敗を`ScopeError`として返す |
| 子のfault / task failure | fail-fastで兄弟へキャンセル要求し、終了を待つ |
| 子が`Err(E)`という値を返した | 自動キャンセルしない。親が処理し、親のErr終了でキャンセルできる |
| 親のErr/早期return/cancel | 未完了の子へキャンセル要求し、join・cleanupしてから退出 |
| deadline超過 | キャンセル要求。強制停止・一定時間内の退出を保証しない |

scope式の正常結果は`Result<T,ScopeError>`。明示joinした失敗は観測済みとして二重報告しない。未観測失敗は生成順で安定して列挙する。scope内の`?`による包含関数の退出でも子タスクの終了待ちを省略しない。

キャンセルは協調的であり、Java/nativeの割り込み非対応呼び出しは停止しないことがある。hard timeoutが必要ならプロセス隔離を使用する。仮想スレッドはCPU並列性を無制限に増やさず、pinningやライブラリ内部ロックも完全には排除しない。

### 5.2 Channelとactor

`Channel<T>`は容量を必須とするbounded channel。満杯ならsendを待機し、キャンセル/closeを型付きエラーで返す。無制限bufferは明示的な別APIとする。`T: Send`を要求し、send成功時に所有権を移譲、失敗時は値を返す。`Share`な不変値の共有はpayloadコピー不要だが、キューの同期コストは存在する。

actorはmailboxから一件ずつ処理し、状態はactor内に閉じる。actor handleはメッセージ送信権限であり、可変状態の共有参照ではない。actorも親scopeに属し、終了・キャンセルを待機する。MVPではメッセージ処理中の再入を認めない。相互待機によるdeadlockを型だけで排除するとは主張しない。

### 5.3 Typestateと外部操作

`resource Reservation<State>`等で状態遷移を表す。`commit`と`release`は`Pending`を消費し、二重使用を拒否する。ただしaffine性だけでは「必ずcommit/releaseする」を保証しないため、未解決の業務resourceは`must_resolve`属性を持ち、全正常/エラー退出経路で解決か復旧記録への移譲を要求する。

```text
commit(Pending) -> Committed | Rejected(Pending) | Unknown(RecoveryToken)
release(Pending) -> Released | Unknown(RecoveryToken)
```

タイムアウトは失敗確定ではない。`RecoveryToken`は操作ID、idempotency key、照会に必要な情報を保持し、信頼された復旧ストアへの永続化を必要とする。クラッシュ時は型検査が実行できないため、外部変更前のdurable intentと冪等APIをアダプター側で実装する。

構造化キャンセルは外部副作用のrollbackではない。不正検知の成功前に残高確保を並列実行するなら、補償と結果不明の復旧が必要。サンプルでは検査を先行させ、決済処理は復旧契約を持つアダプターへ集約する。

## 6. 情報フロー・機密・出典

`Secret<T>`は機密性、`Untrusted<T>`は未検証入力、`Verified<T,S>`は信頼境界を通じて得た出典/検証の証拠を表し、相互に代用しない。

- Secretのmap/連結/シリアライズ結果にもラベルを伝播する。汎用`expose(): T`は提供しない。
- ログ/OTel/JFRにはマスク済みの固定表現か公開値のみを送る。機密を含む例外文・診断・反例・Baggageにも同じ規則を適用する。
- `declassify`にはホスト発行の用途・出力先に束縛されたCapabilityと理由を要求する。操作は監査対象。
- hashや暗号化を一般的な「公開化」とみなさない。出力先に適した変換を信頼されたAPIとして指定する。
- `Untrusted<T>`からの検証は`Result<Validated<T>,ValidationError>`。JSONをparseしただけで業務検証や権限を取得しない。
- 出典の保証を保持できない変換は`Verified`を落とす。AIが任意データに出典ラベルを付けられない。

v0.2の最初の実装は**明示的なデータフロー**を対象とする。機密を条件にした分岐・例外・実行時間などの暗黙フローと側信道は保証外。完全なnoninterferenceやprompt injection耐性を主張しない。program-counterラベル等は後段の検討項目。

## 7. 契約・反例・検証

`requires`は呼び出し前条件、`ensures`は正常な戻り値への後条件。resource invariantは公開操作の出入口で検査する。契約式は純粋で、検査中の副作用・機密出力を禁止する。検証対象の整数は実装のbit幅とoverflow意味論に合わせる。

```rust
pub fn checkedLength(n: Int) -> Result<Long, LengthError> ! {}
    ensures match result { Ok(v) => v >= 0, Err(_) => true }
{
    if n < 0 { Err(LengthError::Negative) } else { Ok(n.toLong()) }
}
```

失敗し得る外部入力は原則`Result`で検証し、契約違反を通常の入力エラーに使わない。実行時の契約違反はfaultとして扱う。契約の評価がfaultになる場合も契約失敗であり、無条件成功にはしない。

検証結果は義務ごとに`proved` / `runtime_checked` / `unproven`を返す。証明器のtimeout/unknownは`unproven`。テスト通過を`proved`に昇格しない。proof-required buildでは未証明を拒否し、それ以外は実行時検査を維持する。公開契約の削除・弱化はAPI変更として差分に表示する。

プロパティテストはseed、生成器version、縮小された反例、ツールチェーンIDを記録する。エージェントに渡すのは機密除去後の再現可能なケース。仕様・契約・hidden testsは実装生成対象と分離し、テストを消して成功扱いにしない。

最初の静的検証は純粋・一次の整数/ADT/境界条件に限定。任意再帰の停止性、並行プログラム全体、外部APIの正しさの自動証明は対象外。

## 8. 観測性・監査・再実行

### 8.1 静的計装

`@trace` / `span`は開始、正常終了、ドメインエラー、キャンセル、faultを区別して記録する。Span終了はcleanupに含め、二重終了を禁止する。`event`からJFRイベントを生成する。計装のための実行時bytecode改変は不要とする。

OTel contextはScopedValueとランタイムadapterで明示的に束縛・継承する。NGJのscope/forkでは自動伝播するが、外部Java executorへはadapterなしに保証しない。Baggageのキー・サイズ・機密性を検査する。O(1)の全体伝播コストは約束しない。

全診断/eventにはbuild ID、source map、symbol IDを関連付けられる。高cardinality属性、payloadサイズ、sampling、exporter backpressureに上限を設ける。通常のtelemetry障害は業務結果を上書きしない。

### 8.2 操作監査

Effect境界では`requested`, `denied`, `succeeded`, `failed`, `unknown`を区別し、operation IDで関連付ける。外部操作の成功はアダプターが確証を得た場合だけ記録する。attemptとcommitを混同しない。

監査必須の操作はsamplingされるOTelとは別のdurable sinkを使う。監査書き込み不能時にfail-closedとするかはホストpolicyで固定し、生成コードが変更できない。トレース欠落を「操作なし」の証拠にしない。

### 8.3 再実行

時刻・乱数・外部I/OをEffect境界で差し替え、入力・応答・seed・依存versionを記録する。replayモードでは実外部副作用を拒否する。fixtureの順序/引数不一致はReplayMismatchとし、黙ってlive呼び出しへfallbackしない。

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

### 9.2 Strict AOTとunsafe

Class-File APIによる走査は既知のreflection、Unsafe、native、dynamic loading、未解決呼び出しを発見する補助であり、任意の依存JARの安全性証明ではない。推移的依存とruntime profileも検査対象。

- NGJの通常コードから実行時reflectionは禁止。
- 選択したAOT profileで許可されるJava内部reflectionは、信頼されたadapterとreachability metadata、Native Imageのbuild/smoke testを必要とする。
- `unsafe java_interop`は監査対象の境界表示であり、許可の発行ではない。restricted sourceでは禁止し、allowlistに固定されたadapter module内だけに限定する。
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

Pratt parser等で式を解析し、Java標準`java.lang.classfile` APIで直接class fileを生成する。Javaソースへのtranspileは必須経路にしない。

型・Effect・所有権・公開契約は共通IRで検査し、バックエンド切替で意味論を変えない。ABI manifestには型/Effect/所有権署名とschema versionを含める。

**Valhalla基盤のプロファイル:**

| target/profile | データ表現 | 前提・制限 |
|---|---|---|
| `valhalla` (基本) | value class + sealed hierarchy | JDK 28 EA + `--enable-preview`を必須とする。data/record/enumはvalue class出力 |
| `valhalla-compat` | identity class fallback | Valhalla未対応環境向け。value意味論は維持するが、JVM最適化なし。移行・テスト用 |
| `leyden` | valhalla + AOT cache | Project Leyden AOTキャッシュ。Valhalla + Leyden両方のサポート状況を検証 |
| `native-image` | valhalla出力からAOT | GraalVM Native Image。現時点ではvalue classサポートは実験段階 |

**AOT互換性の現状（2026年11月時点）:**

| AOTレベル | Valhalla互換 | 状態 |
|-----------|-------------|------|
| JIT (通常JVM) | ✅ 完全対応 | JDK 28 EA + preview |
| Leyden AOT cache | ⚠️ 未検証 | Leyden自体がJDK 28で出荷予定（JEP 544）だが、value classとの組み合わせは未検証 |
| Native Image | ❌ 未サポート | SubstrateVMはvalue classを使用しない。preview機能を避けてnative imageを生成 |

**AOTレベル劣化とエラー:**
- `--aot=leyden`指定時にLeyden/Valhalla組み合わせが未サポートなら、明示エラーまたは`valhalla`へのfallbackを警告付きで提示
- `--aot=native`指定時にvalue classサポートがなければ、`valhalla-compat`出力からのnative-imageビルドを試行するか、明示エラー
- 意味論は全AOTレベルで同一を維持。性能特性のみが異なる

Loom/ScopedValue/StructuredTaskScopeの利用可否・preview flagsはprofileに記録し、使用JDKでのAPI差分はruntime adapterに閉じる。Valhallaのflagsや配置規則を将来の全JDKに固定せず、JDK更新時に再検証する。

Project Leyden AOTとnative-imageを統一的に扱うAOTレベル設計については[AOT設計方向](AOT.md)を参照。

### 10.2 ジェネリクスと特殊化

#### 10.2.1 ジェネリクスはvalue型を含む（言語保証）

NGJのジェネリクスはvalue型を第一級でサポートする。これは**言語レベルの保証**であり、JVM実装の最適化状況に依存しない。

```
// すべて有効なNGJコード
let ints: List<Int> = List.of(1, 2, 3)
let points: List<Point> = List.of(Point(0, 0), Point(1, 1))
let results: List<Result<Int, Error>> = ...
let nested: Option<Option<Int>> = Some(Some(42))
```

**Javaとの違い:**
- Javaではprimitive型はジェネリクスに使用不可（`List<int>`はエラー）
- NGJでは`Int`はvalue型であり、`List<Int>`は自然に記述可能
- boxing/unboxingの概念は言語に存在しない

#### 10.2.2 性能は実装依存（最適化機会）

言語意味論とJVM性能最適化を分離する。

| 機能 | 言語意味論 | JVM最適化（2026年11月時点） |
|------|-----------|---------------------------|
| `data Point { x: Int, y: Int }` | value型、構造的等価性 | ✅ フラット化対象（64bit以下） |
| `Option<Point>` | value型enum | ⚠️ サイズ・ネスト深度による |
| `List<Int>` | Int値のコレクション | ⚠️ ヒープ割当（Parametric JVM待ち） |
| `List<Point>` | Point値のコレクション | ⚠️ ヒープ割当（Parametric JVM待ち） |

**実装状況（リスク節に詳細）:**
- Parametric JVM（ジェネリック特殊化）は研究段階
- JITスカラ化はwarmup後のみ有効
- 64bit超のvalue classフラット化は限定的

#### 10.2.3 特殊化ヒューリスティック

将来のParametric JVM出荷時に備えた設計:
- 参照型ジェネリクスの共有と、値型特殊化を想定
- 特殊化数・コードサイズにbudgetを設け、超過時は共有表現fallback
- 公開ABIは特殊化された内部名を露出しない

`--emit-layout-report`は配置・特殊化・割当情報を出力する候補機能。

**性能無条件保証の禁止:**
「全割当がゼロ」「全ジェネリクスがフラット化」等の無条件な性能保証は行わない。言語は意味論を保証し、性能は実測で評価する。

## 11. 実装段階と未決事項

詳細な完了条件は[VALIDATION.md](VALIDATION.md)を参照する。

**Valhallaは基盤であり、Phase 4ではない。** 以下のフェーズはValhalla対応JDK（JDK 28 EA + preview）を前提とする。

1. **Phase 1:** fn/data/enum（代数的データ型）/Result/Option、module、最小型検査、**Valhalla value class出力**、Typed Holes、構造化診断、型文脈検索、**match網羅性検査**。Effect宣言を保存し、未実装の強制機能を黙って受理しない。
2. **Phase 2:** Effect/Capability、所有権・freeze、Bytes/Arena、Java adapterの信頼境界、restricted build。小さい意味論モデルとnegative testsを先に用意する。
3. **Phase 3:** scope/fork/Channel/actor、Typestate、契約・property tests、明示的情報フロー、OTel/JFR、逐次I/O replay。
4. **Phase 4:** AOTレベル統合（Leyden/native-image互換性行列）、ジェネリック特殊化最適化、限定的証明器、AI生成の比較実験。

AOTレベル（JIT/Leyden/native-image）の統一的なUXとProject Leyden対応については[AOT設計方向](AOT.md)を参照。Phase 4以降の実装計画として位置づける。

**MVPコンパイラの移行:**
現在のMVPコンパイラはJava 25ベースライン向けに実装されている。Valhalla基盤への移行には以下が必要:
- 型検査器: value型/identity型の区別、enum網羅性検査
- コード生成: value class出力（`value`修飾子、sealed hierarchy）
- CI: JDK 28 EA + `--enable-preview`へのピン
- テスト: value意味論（`==`の値比較）の検証

詳細は[MVP_COVERAGE.md](MVP_COVERAGE.md)の移行ノートを参照。

未決事項: 効果多相性の一般化範囲、actor supervisionの拡張、非字句借用、任意handler、暗黙情報フロー、並行replay、証明器の具体選定。未決機能を利用するソースは`unsupported_feature`として拒否し、実装済み保証と混在させない。

## 12. オープンクエスチョンとリスク

### 12.1 JDK/Valhalla関連リスク

| リスク | 影響 | 緩和策 |
|--------|------|--------|
| JDK 28 preview変更 | ソース非互換の可能性 | マイナーバージョンで追従。意味論は維持 |
| JEP 401がfinalにならない | value class出力が使用不可 | valhalla-compat fallback（identity classで意味論維持） |
| Null-Restricted Types (JEP 8316779) 遅延 | Java境界でのnull扱いに制約 | Option変換をコンパイラ側で生成 |
| Parametric JVM 長期遅延 | ジェネリック性能最適化なし | 言語意味論は影響なし。性能は許容 |

### 12.2 ツールチェーン/CI関連リスク

| リスク | 影響 | 緩和策 |
|--------|------|--------|
| Gradle/Maven JDK 28 EA対応遅延 | ビルド困難 | toolchain指定、直接javac呼び出し |
| GraalVM Native Image Valhalla未対応 | native AOT使用不可 | valhalla-compat出力からnative-image、または明示エラー |
| Project Leyden + Valhalla組み合わせ未検証 | leyden AOT使用不可 | JIT fallback、または明示エラー |
| IDE/LSP JDK 28 preview対応 | 開発体験低下 | CLI中心のワークフロー維持 |

### 12.3 設計上の未決事項

| 項目 | 状態 | 方針 |
|------|------|------|
| identity型の詳細構文 | 未確定 | `identity class`暫定。フィードバック後確定 |
| Java sealed型の自動マッピング | 未確定 | アダプター経由を基本。自動推論は後続 |
| 再帰的enumの最大深度 | 未確定 | スタックオーバーフロー回避策を検討 |
| パターンマッチのor-pattern | 未確定 | `Circle { r } | Point => ...`の構文検討中 |

### 12.4 移行パス

現在のJava 25ベースMVPから Valhalla基盤への移行:

1. **即座に可能:** 仕様改訂、設計文書更新
2. **JDK 28 EA利用可能後:** CI移行、コンパイラ出力変更
3. **JDK 28 GA後（2027年3月予定）:** 本番利用開始
4. **preview卒業後:** 安定版としてリリース

## 13. v0.2からの変更（v0.3）

### 13.1 Valhalla基盤への移行

- **Valhallaを基盤に:** オプションのバックエンドから言語設計の根幹へ。Phase 4から全フェーズの前提へ移動
- **Value型デフォルト:** data/record/enumはすべてvalue型（identity-free）。identity型は明示宣言のみ
- **Primitive統一:** Int/Long/Bool等はvalue型。boxed/primitive二重性を廃止
- **Null廃止:** コア言語から`null`を排除。不在は`Option<T>`のみ

### 13.2 Enum代数的データ型

- **Enumをsealed ADTに:** ペイロード付きバリアント（直和型）をサポート
- **Result/Optionを通常enum化:** 特別なビルトインから標準ライブラリのenumへ（`?`ルールは維持）
- **パターンマッチング強化:** ネスト・分解パターン、ガード条件
- **網羅性検査:** 欠落バリアントの正確な診断（AIフィックスループに重要）

### 13.3 ジェネリクスとJVM表現

- **ジェネリクスはvalue型を含む:** `List<Int>`は言語保証。性能最適化は実装依存
- **JVM表現明確化:** enumはsealed hierarchy of value classes
- **AOT互換性:** Leyden/native-imageとの互換性状況を明示

### 13.4 文書構成

- **1.3節追加:** Valhalla基盤とJDKピン方針
- **2.2節再構成:** Value型/Identity型の明確な分離
- **2.4節追加:** Enum代数的データ型の完全仕様
- **10節更新:** Valhalla基盤のプロファイル、AOT互換性
- **11節更新:** フェーズ再定義（Valhalla基盤前提）
- **12節追加:** オープンクエスチョンとリスク

## 14. v0.1からの変更（v0.2）

- AIによる生成・修正を言語設計の目的に追加し、検査器の外部APIを仕様化。
- `Result`に統一し、union代替・一律Option化・自己申告AOT検証を削除。
- 値型=無割当、全I/O=非blocking、チャネル=無lock等の無条件な性能保証を撤回し、測定対象へ変更。
- freeze、Arena、join、キャンセル、cleanup、外部操作の結果不明を詳細化。
- Effect/Capability、Typestate、情報フロー、契約、再実行、信頼境界を追加。
- サンプルは安全なアダプター境界を使う設計例へ更新。コンパイル済みと誤認させない。
