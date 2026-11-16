# NGJ (Next-Gen JVM Language) — Concept & Language Specification Draft

> **Project Name:** `ngj` (Next-Gen JVM Language / Codename: *Vela*)
> **Status:** Draft v0.1 (Architecture & Core Specification)
> **Target Runtime:** OpenJDK 25+ / Project Valhalla EA / GraalVM Native Image

## 1. コンセプトと設計理念 (Core Philosophy)

**NGJ (`ngj`)** は、OpenJDKのモダン基盤（**Project Valhalla / Project Loom / Project Panama / Project Leyden**）を最初から前提（ネイティブサポート）として設計された、静的型付け・式指向のモダンシステム言語である。

既存のJVM言語（Java, Kotlin, Scala）が背負っていた「1990年代のメモリモデル（すべてがヒープ参照）」「実行時リフレクション依存」「例外・Nullの氾濫」という歴史的負債を断ち切り、**Javaの25年分の重厚なエコシステム（ライブラリ・SDK・ドライバ）を安全に継承しながら、RustやGoに匹敵するメモリ効率・並行安全性・シングルバイナリ配布を実現する**ことを目的とする。

### 4つの設計原則
1. **Zero-Cost Data & Memory Layout (Valhalla First):** 不変データはデフォルトでアイデンティティを持たない値型（Valhalla Value Class）とし、スタック／レジスタ割り当てとメモリフラット化を標準化する。
2. **Colorless Structured Concurrency (Loom First):** `async/await` による関数の色分けを排除し、仮想スレッド（Virtual Threads）と構造化並行性をファーストクラスの言語構文として組み込む。
3. **Observability as a Language Feature (OTel & JFR Native):** 分散トレーシング（OpenTelemetry）と低オーバーヘッドプロファイリング（JFR）を外部エージェントではなく言語構文・コンパイラ静的展開・標準ランタイムに統合する。
4. **Zero-Reflection & Strict AOT Interop:** 実行時リフレクションを禁止し、Java 25+ 標準の `java.lang.classfile` API による直接バイトコード出力と GraalVM Native Image によるミリ秒起動シングルバイナリを第一級ターゲットとする。

---

## 2. 構文および型システム (Syntax & Type System)

Javaの構文上の曖昧さや「Javaと似た見た目で挙動が違う罠」を避けるため、パーサーがシンプルかつ一意に解析できるモダンな式指向構文（`fn`, `let`, `data`, `actor`, `resource`）を採用する。

### 2.1 「値 (Value)」と「状態・リソース (Identity)」の厳格な分離

| キーワード | JVM表現 (Bytecode) | 性質と用途 |
| :--- | :--- | :--- |
| **`data` / `enum`** | **Valhalla Value Class**<br>(`ACC_IDENTITY` なし) | 不変ドメインモデル、代数的データ型（ADT）、Newtypeラッパー。参照同一性（`===`）やロックを持たず、連続配列・レジスタ上にフラット配置される。 |
| **`actor` / `resource`** | **Identity Object**<br>(`ACC_IDENTITY` あり) | 可変状態を持つ軽量アクター、DBコネクション、ソケット等のライフサイクル管理対象。`===` による同一性比較が可能。 |

### 2.2 スコープ限定可変性と凍結モデル (Scoped Mutability & Freeze)
言語全体のデフォルトは **完全不変 (Immutable)** である。ローカルでのデータ構築パフォーマンスを確保するため、スコープ限定の可変性とゼロコピー凍結を提供する。

* **`build` ブロック:** ローカルスコープ内でのみ可変コレクション（`MutableList<T>`）やバッファ（`BytesBuilder`）の操作を許可する。
* **ゼロコピー凍結 (Freeze):** `build` スコープを抜ける瞬間に所有権を移譲し、メモリコピーなしで不変型（`List<T>`, `Bytes`）へ凍結する。凍結後の元の可変参照へのアクセスはコンパイルエラーとする。

### 2.3 Null安全と値型エラーハンドリング
* `null` および検査／非検査例外の送出を言語仕様から排除する。
* 失敗や不在は Valhalla Value Class として実装された **`Result<T, E>`** および **`Option<T>`**（または共用体型 `T | E`）で表現する。値型であるため、`?` 演算子によるエラー伝播やパイプライン演算子（`|>`）を多用してもヒープ割り当て（ボクシング）は一切発生しない。

---

## 3. ジェネリクス特殊化戦略 (Hybrid Specialization)

C++テンプレートのようなバイナリ肥大化を防ぎつつ、ボクシングによる性能劣化を根絶するため、**コンパイラの選択的特殊化とJVMの動的最適化のハイブリッド方式**を採用する。

1. **参照型同士の共有 (Type Erasure):** 型引数に Identity Object が渡された場合は単一のバイトコードを共有する。
2. **プリミティブおよび小型値型の自動特殊化:** プリミティブ型（`Int`, `Long`, `Double`, `Bool` 等）および **128bit以下の小型 Valhalla Value Class** が型引数に入った場合のみ、コンパイラが特殊化バイトコード（プリミティブ配列展開や非ボクシングシグネチャ）を自動生成する。
3. **細部最適化のJVM委譲:** ループアンローリング、投機的インライン化、SIMD（Vector API）ベクトル化、大型値型のスカラ置換は、JVM（C2 JIT / Valhalla Parametric VM）の最適化パスに委ねる。

---

## 4. 標準ライブラリ (StdLib): 不変・ゼロコピー基盤

* **不変コレクション (`List<T>`, `Map<K, V>`, `Set<T>`):** 構造共有（Structural Sharing）と Valhalla Value Class ノードを用いた永続データ構造を標準とし、仮想スレッド間のロックフリー共有を保証する。
* **ゼロコピー・スライス (`Bytes` / `Slice<T>`):** Project Panama (`MemorySegment`) をラップした不変スライス型（セグメント参照＋オフセット＋長さのValue Class）を標準のバイナリ型とする。ネットワークやファイルI/Oの切り出し（`slice`）においてメモリコピーおよびGCアロケーションをゼロにする。
* **Arena と並行スコープの統合:** オフヒープメモリ（`MemorySegment` / `mmap`）の生存期間を管理する `Arena` を構造化並行性の `scope` ライフタイムと統合し、Use-after-free をコンパイル時およびスコープ境界で防止する。

---

## 5. 並行処理モデル (Project Loom Native Integration)

* **仮想スレッド標準実行:** すべてのブロッキング風I/O呼び出しは仮想スレッド上でノンブロッキングに実行される。
* **構造化並行性 (`scope` / `fork`):** `scope { ... }` 内で `fork` されたすべての仮想スレッドは、親スコープを抜ける前に完了またはキャンセルされることを言語レベルで保証する（孤児スレッドの禁止）。
* **ゼロコピー・チャネル (`Channel<T>`):** 仮想スレッド間の通信には不変かつ値型（または凍結済みデータ）を流すチャネルを用い、防御的コピーやロックを不要にする。

---

## 6. 観測可能性の標準統合 (OTel & JFR First-Class Support)

* **OTelコンテキストの自動伝播 (`ScopedValue` 統合):** OTelの `Context`（TraceID, SpanID, Baggage）を `java.lang.ScopedValue` にバインドし、`scope` / `fork` による仮想スレッド生成時にランタイムが O(1) で自動継承する。
* **静的計装 (`span` / `@trace`):** コンパイル時に `Span` の開始・終了・エラー記録をバイトコードへ直接インライン展開する。`-javaagent` による実行時バイトコード改変が不要なため、AOTネイティブイメージでも完全動作する。
* **PII（機密情報）の型レベル保護:** `Secret<T>` 型または `@sensitive` マーカーが付与された値は、OTel属性・ログ・JFRイベント出力時に標準シリアライザが自動的にマスク（`***`）する。
* **JFRネイティブイベント (`event`):** `event` キーワードから `jdk.jfr.Event` サブクラスを自動生成し、JFR Event Streaming 経由で OTLP へ低オーバーヘッドでブリッジする。

---

## 7. コンパイラアーキテクチャとJava相互運用 (Compiler & Strict AOT Interop)

### 7.1 コンパイルパイプライン (`ngjc`)
トランスパイラ（`.java` 出力）ではなく、Java 25+ 標準の **`java.lang.classfile` API** を用いた **JVMバイトコード（`.class`）直出力コンパイラ**として実装する。

```text
[NGJ Source (.ngj)]
       │
       ▼  (Lexer / Pratt Parser / Type Checker / Borrow & Freeze Analyzer)
[Typed IR]
       │
       ├──▶ --target=java25 (現行LTS / GraalVM Native Image 安定版)
       │      └─ `data` を final クラスとして出力（特殊化ジェネリクスはコンパイラ側で展開）
       │
       └──▶ --target=valhalla (Project Valhalla EA / Java 28+)
              └─ `data` を `ACC_IDENTITY` なしの真の Value Class としてバイトコード出力
                     │
                     ▼  Output: .class / .jar + reachability-metadata.json
             ┌───────┴───────┐
             ▼               ▼
       [Standard JVM]  [GraalVM native-image]
       (C2 JIT / ZGC)  (AOT Single Binary)
```

### 7.2 Java既存ライブラリとの境界設計 (Strict AOT Mode)
Javaの強力なインフラライブラリ（Netty, JDBC, クラウドSDK, Tink 等）を安全に利用するため、Java境界に厳格な静的検査を課す。

* **自動境界サニタイズ:** Javaメソッドの戻り値は自動的に `Option<T>` へ、例外は `Result<T, JavaException>` へゼロオーバーヘッドで変換される。
* **汚染のコンパイル時ブロック:** コンパイラが `Class-File API` で依存 JAR のバイトコードを静的に走査し、**リフレクション（`java.lang.reflect`）、`Unsafe`、または `synchronized` ブロック内のブロッキングI/O** を検出した場合、デフォルトでコンパイルエラーとする。
* **明示的隔離 (`unsafe java_interop`):** 上記を含むレガシーJavaライブラリを利用する場合は、`unsafe java_interop { ... }` ブロックで明示的に隔離し、AOT到達可能性メタデータを記述する契約とする。

---

## 8. 開発ロードマップ (MVP Implementation Plan)

1. **Phase 1: 最小コンパイラ骨格 (`ngjc` v0.1)**
   * Pratt Parser による AST 構築と、Java標準 `Class-File API` による `fn` / `data` の `.class` 直接出力（`--target=java25` モード）。
2. **Phase 2: 並行性と観測性のランタイム統合**
   * `scope` / `fork` の `StructuredTaskScope` マッピング、`ScopedValue` による OTel 自動伝播、`span` / `event` の静的展開。
3. **Phase 3: 不変・ゼロコピー標準ライブラリとStrict AOT検査器**
   * `MemorySegment` ベースの `Bytes`、`build` 凍結ブロック、`Class-File API` による依存JARのバイトコード静的検査と `reachability-metadata.json` 自動生成。
4. **Phase 4: Project Valhalla ネイティブバックエンド (`--target=valhalla`)**
   * OpenJDK Valhalla EA ビルド上での `ACC_IDENTITY` なし Value Class 出力とメモリフラット化・スループット検証。
