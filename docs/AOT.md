# AOT設計方向 — post-MVP/post-v0.3

> Status: 設計方向。実装は将来のフェーズ。
> 本文書は言語仕様v0.3以降の拡張計画であり、MVP要件ではない。
> Valhalla基盤との整合性: Refs #7, [SPECIFICATION.md](SPECIFICATION.md) 1.3節

## 1. 設計思想

NGJは最新のJVMツールチェーンを前提とし、レガシー互換性の制約を負わない。AIエージェント時代に最適化された言語設計を維持しつつ、起動性能・配布形式・実行特性の多様なニーズに対応する。

**Valhalla基盤:** NGJ v0.3はProject Valhalla value classesを言語設計の基盤とする（[SPECIFICATION.md](SPECIFICATION.md) 1.3節参照）。AOT設計はこのValhalla基盤を前提とし、value classの意味論を全AOTレベルで維持する。

対象とする最新ツールチェーン：

- **JDK 28 EA + `--enable-preview`**: Valhalla value classes（JEP 401）の必須基盤
- **Project Leyden AOT** (JDK 28+): AOTキャッシュ、事前クラスロード・リンク、メソッドプロファイリング
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

### 4.2 Valhalla + AOT互換性（実装状況・リスク）

**現状（2026年11月時点）:** Valhalla value classes（JEP 401）とAOT技術の組み合わせは未検証または未サポートの段階にある。これは実装状況であり、言語意味論の妥協理由ではない。

| AOTレベル | Valhalla互換 | 状態・リスク |
|-----------|-------------|-------------|
| `jit` (通常JVM) | ✅ 完全対応 | JDK 28 EA + `--enable-preview`で動作 |
| `leyden` | ⚠️ 未検証 | Leyden自体がJDK 28で出荷予定（JEP 544）だが、value classとの組み合わせは未検証。Leyden AOTキャッシュがvalue class layoutを正しく扱うか要検証 |
| `native` | ❌ 未サポート | SubstrateVM（GraalVM Native Image）はvalue classを使用しない。preview機能を避けてnative imageを生成する設計（[Graal on the Road to Valhalla](https://epub.jku.at/download/pdf/11912841.pdf)参照） |

**意味論への影響なし:** AOTレベルでvalue classが利用不可能な場合でも、言語の意味論（value型のデフォルト、構造的等価性、null廃止）は変更しない。コンパイラが以下の対応を行う:
- `--aot=leyden`でLeyden/Valhalla組み合わせが未サポートなら、明示エラーまたはJIT fallbackを警告付きで提示
- `--aot=native`でvalue classサポートがなければ、`valhalla-compat`モード（identity classで意味論維持）からのnative-imageビルドを試行するか、明示エラー

### 4.3 CI行列

AOTレベル × バックエンド × OS/archの組み合わせでCI行列が爆発する可能性がある。段階的な導入戦略：

1. Phase 1: `jit` + `valhalla`（JDK 28 EA + preview）— Valhalla基盤MVP
2. Phase 2: `native`をLinux/amd64で追加（valhalla-compat経由）
3. Phase 3: `leyden`をJDK 28 GA + Valhalla組み合わせ検証後に追加
4. Phase 4: 全組み合わせの互換性行列、Valhalla GA後の最適化

### 4.4 GraalVMバージョンとJDK 28の整合

- JDK 28 GA（2027年3月予定）とGraalVM for JDK 28のリリース時期
- GraalVM JITのValhalla対応（進行中）とNative Image（SubstrateVM）対応（未定）の区別
- Leyden AOT機能のJDK 28での成熟度とvalue class対応状況
- 必要なGraalVMバージョンの固定方針

### 4.5 CLI JSONでのAOTレベル報告

`ngjc build`のJSON出力にAOTレベル情報を含める形式：

```json
{
  "schema_version": "ngj.build/1",
  "aot_level": "leyden",
  "valhalla_mode": "native",
  "aot_details": {
    "cache_path": "build/leyden-cache",
    "jdk_version": "28",
    "preview_enabled": true,
    "leyden_features": ["class-loading", "method-profiling"],
    "valhalla_compat_fallback": false
  }
}
```

### 4.6 Leyden機能の段階的採用

Project Leydenは複数の機能を段階的に提供する予定：

- Class Data Sharing (CDS) の拡張
- AOTクラスロード・リンク
- プロファイルに基づくAOTコンパイル

どの機能をどの時点で採用するかは、JDKリリースの安定性とValhalla value classとの互換性検証結果を見て決定する。

## 5. 関連文書

- [言語仕様](SPECIFICATION.md): 1.3節（Valhalla基盤）、10節（バックエンド・AOT互換性）、12節（リスク）
- [検証計画](VALIDATION.md): TARGET-01/02（バックエンド間の意味論一致）、VALUE-*（value型検証）
- [MVP Coverage](MVP_COVERAGE.md): Valhalla移行チェックリスト
- [Research basis](RESEARCH.md): Valhalla JEP/論文の出典

## 6. 変更履歴

- 2026-11-17: 初版作成。post-MVP/post-v0.3の設計方向として文書化。
- 2026-11-17: Valhalla基盤との整合性更新。AOT互換性の実装状況・リスクを追記。
