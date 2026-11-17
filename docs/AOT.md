# AOT設計方向 — post-MVP/post-v0.3

> Status: 設計方向。実装は将来のフェーズ。
> 本文書は言語仕様v0.3以降の拡張計画であり、MVP要件ではない。
> Valhalla基盤との整合性: Refs #7, [SPECIFICATION.md](SPECIFICATION.md) 1.3節

## 1. 設計思想

NGJは最新のJVMツールチェーンを前提とし、レガシー互換性の制約を負わない。AIエージェント時代に最適化された言語設計を維持しつつ、起動性能・配布形式・実行特性の多様なニーズに対応する。

**Valhalla + Leyden基盤:** NGJ v0.3はProject Valhalla value classes（JEP 401）とStrict Field Initialization（JEP 539）を言語設計の基盤とする（[SPECIFICATION.md](SPECIFICATION.md) 1.3節参照）。AOT設計はこのValhalla基盤を前提とし、value classの意味論を全AOTレベルで維持する。同時に、2025年9月のOracleによるGraalVMのJavaエコシステム・トレイン切り離しと2026年春のOpenJDK Project Galahad/Metropolis解散を受け、**OpenJDK標準のAOT本線はProject Leydenに収斂**している。

対象とする最新ツールチェーン：

- **JDK 28 EA + `--enable-preview`**: Valhalla value classes（JEP 401）とStrict Field Initialization（JEP 539）の必須基盤
- **Project Leyden AOT（標準AOT本線）**: JDK 24（JEP 483）→ JDK 25 LTS（JEP 514/515）→ JDK 26（JEP 516）→ JDK 28（JEP 544）へ段階導入されたAOTキャッシュ・事前リンク・メソッドプロファイル・AOTコードコンパイル
- **GraalVM native-image（外部・任意配布ターゲット）**: 単一ネイティブバイナリや極小メモリフットプリントが必須な場合向けの外部ツールチェーン（Community/独立版GraalVM）

NGJの言語・標準ライブラリは、Project LeydenのAOTキャッシュ無効化条件やIntegrity by Default（JEP 471/472/500）、Project Lilliputの22bitクラスポインタ上限（JEP 534）とも整合する**closed-world friendly**な設計を維持する。

## 2. AOTレベル

`ngjc`は単一のUXでシームレスに選択可能なAOTレベルを提供する。ビルド時の単一オプションで出力形式を切り替え、言語の意味論は全レベルで同一とする。

| レベル | 提案フラグ | 出力 | 位置づけ・用途 |
|--------|-----------|------|------|
| `jit` | `--aot=jit` | 通常のJARファイル | 既定。エージェントによる修正・検査ループ、デバッグ向け |
| `leyden` | `--aot=leyden` | Leyden AOTキャッシュ付きJAR | **標準AOT本線。** 高速起動 + 即時JIT最適化（JEP 483/514/515/516/544）。サーバー・CLI向け |
| `native` | `--aot=native` | native-imageバイナリ | **外部任意ターゲット。** 単一バイナリ配布・最小RSS向け（独立系GraalVM使用） |

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

AOTの全レベル（特にProject Leyden AOTキャッシュとIntegrity by Default環境）で制約なく動作することを保証するため、NGJは以下の言語レベル制約を維持する。これらはv0.3仕様の既存原則を明確化したものである。

### 3.1 Closed-world friendly & Integrity by Default適合

- NGJコードからの実行時リフレクション禁止（仕様1.1, 原則6より）
- 動的クラスロード・カスタムクラスローダ禁止（Leyden AOTキャッシュ無効化の回避、およびLilliput JEP 534の約400万クラス上限への配慮）
- 実行時バイトコード生成・動的プロキシ禁止
- `Class.forName()`、`Method.invoke()`、`sun.misc.Unsafe`、JNIは標準ライブラリに含めない（JEP 471/472/498/500のIntegrity by Default路線に整合）

### 3.2 Java相互運用の制約

Java相互運用（将来実装時）は以下の制約に従う：

- **静的に解決可能**: 呼び出し先のクラス・メソッドはコンパイル時に確定
- **FFM中心とIntegrity宣言**: ネイティブ連携はJNIを禁じFFM（JEP 454）+ `--enable-native-access`許可対象アダプターのみに限定
- **reachability metadata生成**: `--aot=native`使用時に必要なメタデータを自動生成または要求
- 動的な要素を含むJava APIを呼び出す場合、アダプター側でメタデータ・`--illegal-final-field-mutation`要件等を明示的に提供

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
- 不変遅延値には`LazyConstant`（JEP 547）等のAOT親和的プリミティブを利用可能
- 全APIがclosed-world analysisおよびLeyden学習実行で完全に解決可能

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

**現状（2026年11月時点）:** Project LeydenはJDK 25〜26時点で`valhalla-compat`（Java 25+）向けに実運用可能だが、JDK 28のValhalla value classes（JEP 401/539）とLeyden AOTコードコンパイル（JEP 544）の組み合わせはEA検証段階にある。これは実装状況であり、言語意味論の妥協理由ではない。

| AOTレベル | Valhalla互換 | 状態・リスク |
|-----------|-------------|-------------|
| `jit` (通常JVM) | ✅ 完全対応 | JDK 28 EA + `--enable-preview`で動作 |
| `leyden` | ⚠️ 検証中 | `valhalla-compat`（JDK 25+）はJEP 483/514/515/516で動作可能。JDK 28 JEP 544 × JEP 401/539の結合キャッシュは要検証 |
| `native` | ❌ 未サポート（外部依存） | 独立系GraalVM SubstrateVMはJEP 401/539を未実装（[Graal on the Road to Valhalla](https://epub.jku.at/download/pdf/11912841.pdf)参照）。`valhalla-compat`経由で生成 |

**意味論への影響なし:** AOTレベルでvalue classが利用不可能な場合でも、言語の意味論（value型のデフォルト、構造的等価性、null廃止）は変更しない。コンパイラが以下の対応を行う:
- `--aot=leyden`でLeyden/Valhalla組み合わせが未サポートなら、明示エラーまたはJIT / `valhalla-compat`へのfallbackを警告付きで提示
- `--aot=native`でvalue classサポートがなければ、`valhalla-compat`モード（identity classで意味論維持）からのnative-imageビルドを試行するか、明示エラー

### 4.3 CI行列

AOTレベル × バックエンド × OS/archの組み合わせでCI行列が爆発する可能性がある。OpenJDK本線に沿った段階的な導入戦略：

1. Phase 1: `jit` + `valhalla`（JDK 28 EA + preview）および `valhalla-compat`（JDK 25 LTS）— Valhalla基盤MVP
2. Phase 2: `leyden`（まずJDK 25/26 `valhalla-compat`の`JEP 514/515/516`、続けてJDK 28 EAの`JEP 544` + Valhalla結合検証）
3. Phase 3: `native`を任意プロファイルとしてLinux/amd64で追加（`valhalla-compat` + 外部GraalVM CE）
4. Phase 4: 全組み合わせの互換性行列、Valhalla GA後の最適化

### 4.4 ツールチェーン整合（OpenJDK本線 vs 外部GraalVM）

2025年9月にOracleが *"Detaching GraalVM from the Java Ecosystem Train"* を発表し、JDK 24をもってJava SE製品版GraalVM（Graal JIT / Native Image）が切り離され、2026年春にOpenJDKのProject Galahad/Metropolisも解散した。これによりNGJでは以下を区別して管理する：

- **OpenJDK本線（必須サポート）**: JDK 25 LTS〜JDK 28 GAにおけるProject Leyden（AOTキャッシュ/コードコンパイル）とValhalla（JEP 401/539）の整合
- **外部GraalVM（任意サポート）**: Community Edition等の独立リリースサイクルに追従し、`--aot=native`は外部ツールチェーン存在時のみ有効化
- バージョン・ハッシュ固定方針をビルドマニフェスト（`abi_manifest`）で分離管理

### 4.5 CLI JSONでのAOTレベル報告

`ngjc build`のJSON出力にAOTレベル情報を含める形式：

```json
{
  "schema_version": "ngj.build/1",
  "aot_level": "leyden",
  "target": "valhalla",
  "aot_details": {
    "cache_path": "build/leyden-cache",
    "jdk_version": "28",
    "preview_enabled": true,
    "leyden_features": ["class-loading", "method-profiling", "object-caching", "code-compilation"],
    "valhalla_compat_fallback": false
  }
}
```

### 4.6 Leyden機能の段階的採用

Project Leydenはすでに複数のJEPを段階的にリリースしている。NGJはJDKターゲットに応じて段階採用する：

| JDK | JEP | 機能 | NGJでの利用段階 |
|---|---|---|---|
| JDK 24 | JEP 483 | AOT Class Loading & Linking | 基盤（`valhalla-compat`で利用可） |
| JDK 25 LTS | JEP 514 / 515 | AOT Command-Line Ergonomics (`-XX:AOTCacheOutput`)・Method Profiling | Phase 2 既定ターゲット |
| JDK 26 | JEP 516 | AOT Object Caching with Any GC（ZGC対応含む） | 低レイテンシGC併用時 |
| JDK 28 | JEP 544 | Ahead-of-Time Code Compilation（最適化ネイティブコード保存） | Valhalla結合検証後に本採用 |

## 5. 関連文書

- [言語仕様](SPECIFICATION.md): 1.3–1.4節（Valhalla・コアJVM基盤）、9.2節（Integrity by DefaultとStrict AOT）、10節（バックエンド・AOT互換性）、12節（リスク）
- [検証計画](VALIDATION.md): TARGET-01/02（バックエンド間の意味論一致）、VALUE-*（value型検証）
- [MVP Coverage](MVP_COVERAGE.md): Valhalla移行チェックリスト
- [Research basis](RESEARCH.md): Valhalla/Leyden/Integrity JEP・出典

## 6. 変更履歴

- 2026-11-17: 初版作成。post-MVP/post-v0.3の設計方向として文書化。
- 2026-11-17: Valhalla基盤との整合性更新。AOT互換性の実装状況・リスクを追記。
- 2026-11-17: GraalVM切り離しとProject Galahad解散・JDK 24〜28 Leyden JEP群（483/514/515/516/544）・JEP 539/Integrity/Lilliputを反映し、Project Leydenを標準AOT本線へ再定義。
