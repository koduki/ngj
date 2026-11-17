# AOT設計方向 — post-MVP/post-v0.3

> Status: 設計方向。実装は将来のフェーズ。
> 本文書は言語仕様v0.3以降の拡張計画であり、MVP要件ではない。

## 1. 設計思想

NGJは最新のJVMツールチェーンを前提とし、レガシー互換性の制約を負わない。AIエージェント時代に最適化された言語設計を維持しつつ、起動性能・配布形式・実行特性の多様なニーズに対応する。

対象とする最新ツールチェーン：

- **Project Leyden AOT** (JDK 24-25+): AOTキャッシュ、事前クラスロード・リンク、メソッドプロファイリング
- **GraalVM native-image**: ネイティブバイナリへのAOTコンパイル

NGJの言語・標準ライブラリは、これらのAOT技術を制約なく活用できる**closed-world friendly**な設計を維持する。

## 2. AOTレベル

`ngjc`は単一のUXでシームレスに選択可能なAOTレベルを提供する。ビルド時の単一オプションで出力形式を切り替え、言語の意味論は全レベルで同一とする。

| レベル | 提案フラグ | 出力 | 用途 |
|--------|-----------|------|------|
| `jit` | `--aot=jit` | 通常のJARファイル | エージェントによる修正・検査ループ。JVM上で即座に実行・デバッグ可能 |
| `leyden` | `--aot=leyden` | Leyden AOTキャッシュ付きJAR | 高速起動 + JIT最適化によるピーク性能。サーバー・長時間実行向け |
| `native` | `--aot=native` | native-imageバイナリ | 配布・デプロイ向け。起動時間最小化、メモリフットプリント削減 |

### 2.1 意味論の同一性

AOTレベルはビルド/実行の最適化パスのみを変更する。以下は全レベルで同一でなければならない：

- 型検査結果
- Effect/Capability検査
- 契約検証結果
- 実行時の観測可能な振る舞い（タイミング・性能を除く）

AOTレベルの違いによってコンパイルが通る/通らない、テストが成功/失敗するといった差異は設計上の欠陥として扱う。

### 2.2 エージェントループとAOTレベルの分離

`ngjc check`、`ngjc context`、`ngjc inspect`等のエージェント向けコマンドは、AOTレベルに依存せず高速に動作しなければならない。

- 型検査・穴の文脈取得・シンボル検索はAOTキャッシュやネイティブコンパイルを待たない
- AOTレベルは`ngjc build`と`ngjc run`（将来）の出力にのみ影響する
- エージェントの修正・検査ループは常に`jit`相当の速度で動作する

## 3. 言語レベルの制約

AOTの全レベルで問題なく動作することを保証するため、NGJは以下の言語レベル制約を維持する。これらはv0.3仕様の既存原則を明確化したものである。

### 3.1 Closed-world friendly

- NGJコードからの実行時リフレクション禁止（仕様1.1, 原則6より）
- 動的クラスロード禁止
- 実行時バイトコード生成禁止
- `Class.forName()`、`Method.invoke()`等の動的APIは標準ライブラリに含めない

### 3.2 Java相互運用の制約

Java相互運用（将来実装時）は以下の制約に従う：

- **静的に解決可能**: 呼び出し先のクラス・メソッドはコンパイル時に確定
- **reachability metadata生成**: native-imageビルドに必要なメタデータを自動生成または要求
- 動的な要素を含むJava APIを呼び出す場合、アダプター側でメタデータを明示的に提供

```text
// 将来の構文例（提案）
@java_interop(
    reachability = "adapters/jdbc.reachability.json",
    aot_verified = ["jit", "leyden", "native"]
)
adapter JdbcAdapter { ... }
```

### 3.3 標準ライブラリの制約

NGJ標準ライブラリは以下を保証する：

- リフレクションを使用しない
- 動的プロキシを使用しない
- バイトコード生成を使用しない
- 全APIがclosed-world analysisで完全に解決可能

## 4. 未決事項（Open Questions）

以下は実装フェーズで検討・決定する事項である。

### 4.1 CLIフラグ命名

```text
候補A: --aot=jit|leyden|native
候補B: --target=jvm|leyden|native
候補C: --profile=dev|optimized|native
```

フラグ名は既存の`--target`（バックエンド選択）との関係を明確にする必要がある。`valhalla`バックエンドとの組み合わせも考慮する。

### 4.2 CI行列

AOTレベル × バックエンド × OS/archの組み合わせでCI行列が爆発する可能性がある。段階的な導入戦略：

1. Phase 1: `jit` + `java25`のみ（MVP）
2. Phase 2: `native`をLinux/amd64で追加
3. Phase 3: `leyden`をJDK 25 GAで追加
4. Phase 4: 全組み合わせの互換性行列

### 4.3 GraalVMバージョンとJava 25の整合

- Java 25のGA時期とGraalVM for JDK 25のリリース時期
- Leyden AOT機能のJDK 25での成熟度
- 必要なGraalVMバージョンの固定方針

### 4.4 CLI JSONでのAOTレベル報告

`ngjc build`のJSON出力にAOTレベル情報を含める形式：

```json
{
  "schema_version": "ngj.build/1",
  "aot_level": "leyden",
  "aot_details": {
    "cache_path": "build/leyden-cache",
    "jdk_version": "25",
    "leyden_features": ["class-loading", "method-profiling"]
  }
}
```

### 4.5 Leyden機能の段階的採用

Project Leydenは複数の機能を段階的に提供する予定：

- Class Data Sharing (CDS) の拡張
- AOTクラスロード・リンク
- プロファイルに基づくAOTコンパイル

どの機能をどの時点で採用するかは、JDKリリースの安定性を見て決定する。

## 5. 関連文書

- [言語仕様](SPECIFICATION.md): 1.1節の原則6（Static interop and AOT）、9.2節（Strict AOTとunsafe）、10節（バックエンド）
- [検証計画](VALIDATION.md): TARGET-01（バックエンド間の意味論一致）、Native Image互換性試験
- [MVP Coverage](MVP_COVERAGE.md): Native Image/Valhallaは明示的にMVP外

## 6. 変更履歴

- 2026-11-17: 初版作成。post-MVP/post-v0.3の設計方向として文書化。
