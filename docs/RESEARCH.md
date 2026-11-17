# Research basis and design decisions

調査基準日: 2026-11-17。以下はNGJの設計判断に利用した一次資料。既存言語での実験や形式モデルをNGJの実証結果とみなさない。

## AI生成・型システム関連

| 資料 | 公開情報・位置づけ | NGJで採用する部分 | 適用の限界 |
|---|---|---|---|
| [Generative Compilation](https://arxiv.org/abs/2607.13921) | 2026-07、プレプリント | 生成途中のcompiler feedback | NGJではまず明示的hole。任意prefixの健全性は別に検証 |
| [Language-Based Agent Control](https://arxiv.org/abs/2605.12863v3) | 2026-11-07改訂、プレプリント | Effect/Capability、不透明型、情報フロー | Haskellの実装/保証はJVM境界に自動継承されない |
| [ETAS](https://arxiv.org/abs/2607.17780) | 2026-07、研究プロトタイプ | 副作用と操作履歴、承認/権限の分離 | エージェントDSL全体や任意handlerは採用しない |
| [Type-Constrained Code Generation](https://doi.org/10.1145/3729274) | PLDI 2025 | 型制約を生成側へ公開 | token-level constrained decodingは任意拡張 |
| [Statically Contextualizing LLMs with Typed Holes](https://doi.org/10.1145/3689728) | OOPSLA 2024、[著者版](https://arxiv.org/abs/2409.00921) | 型付き穴、関連APIの取得、LSPとの接続 | 新言語の学習データ不足を完全に解決するとは限らない |
| [Djinnlang](https://people.seas.harvard.edu/~chong/pubs/vericodegen2026djinnlang.pdf) | 2026年公開、AI for Verifiable Coding workshop表記の論文 | 固定した契約と実装/証明の分離 | 全プログラムの仕様一意性や自動証明は必須にしない |

ETASの研究段階については[プロジェクトの説明](https://github.com/etas-project/etas)と[APLAS/ATVA 2026 NIERプログラム](https://conf.researchr.org/details/aplas-atva-2026/aplas-atva-2026-aplas-nier/3/Etas-An-Effect-Typed-Language-for-Agent-Systems)も参照。基準日時点で予定された発表を、完了済みの評価・実用化と扱わない。

## Project Valhalla関連（言語基盤）

NGJはProject Valhallaのvalue classesを言語設計の基盤とする。以下は設計判断に利用した一次資料。

| 資料 | 公開情報・位置づけ | NGJでの採用 | 適用の限界 |
|---|---|---|---|
| [JEP 401: Value Objects (Preview)](https://openjdk.org/jeps/401) | JDK 28統合済み、2026-08更新 | value classの意味論・構文・JVM表現 | Preview段階。将来変更の可能性 |
| [JEP draft 8316779: Null-Restricted Value Class Types](https://openjdk.org/jeps/8316779) | Draft、2026-05更新 | Java境界でのnull制限の参考 | ドラフト段階。NGJはOption優先 |
| [JEP draft 8303099: Null-Restricted and Nullable Types](https://openjdk.org/jeps/8303099) | Draft、2025-07更新 | `T!`/`T?`構文の参考 | ドラフト段階 |
| [JEP 402: Enhanced Primitive Boxing (Draft)](https://openjdk.org/projects/valhalla/) | Draft | primitive統一の参考 | ドラフト段階 |
| [Project Valhalla: Value Objects](https://openjdk.org/projects/valhalla/value-objects) | 公式ドキュメント | value classの設計思想 | JDK 28時点の状態 |
| [Parametric VM design notes](https://openjdk.org/projects/valhalla/design-notes/parametric-vm/parametric-vm) | 設計ノート、研究段階 | ジェネリック特殊化の将来計画 | 研究段階。出荷時期未定 |
| [valhalla-dev ML: Value objects in 28 EA](https://mail.openjdk.org/archives/list/valhalla-dev@openjdk.org/message/7CUEBH4ZEQFCE4WUELGZP4GFABER4XMG/) | 2026-08-10 | JDK 28統合の公式アナウンス | — |

**JDK 28での状態（2026年11月時点）:**
- JEP 401 Value Objects: Preview統合済み。`--enable-preview`必須
- JDK標準クラスのvalue class化: `Integer`, `Long`, `Optional`, `LocalDate`等30クラス
- `==`の意味論変更: value objectはフィールド値比較
- GA予定: 2027年3月9日

**未出荷機能:**
- Null-Restricted Types: ドラフト
- Enhanced Primitive Boxing: ドラフト
- Parametric JVM（ジェネリック特殊化）: 研究段階

## AOT/Native Image関連

| 資料 | 公開情報・位置づけ | NGJでの採用 | 適用の限界 |
|---|---|---|---|
| [JEP 544: Ahead-of-Time Code Compilation](https://openjdk.org/jeps/544) | JDK 28ターゲット | Leyden AOTの参考 | Valhalla組み合わせ未検証 |
| [Graal on the Road to Valhalla](https://epub.jku.at/download/pdf/11912841.pdf) | 学術論文 | GraalVM JITのValhalla対応状況 | Native Image（SubstrateVM）は対象外 |
| [JVM Weekly vol. 180](https://www.jvm-weekly.com/p/project-valhalla-explained-how-a) | 2026解説記事 | Valhalla/特殊化の現状整理 | ジャーナリズム |

**AOT互換性の現状:**
- GraalVM Native Image: SubstrateVMはvalue classを使用しない。preview機能を避けてnative imageを生成
- Project Leyden: JDK 28で出荷予定だが、value classとの組み合わせは未検証
- 詳細は[AOT設計方向](AOT.md)を参照

## JVM側の設計補正

- [Oracle JDK 24 migration guide](https://docs.oracle.com/en/java/javase/24/migrate/significant-changes-jdk-24.html) / [JEP 491](https://openjdk.org/jeps/491): synchronizedによる仮想スレッドpinningの改善。synchronized一律拒否から、対象JDKと実際の危険操作を分ける設計へ変更。

## 研究からの推論とNGJ固有の選択

論文が直接示すのは各研究の対象言語・課題・モデルでの性質であり、本仕様は以下をNGJ側の設計判断として追加する。

1. CLIとLSPで同じ検査器を利用し、ブラックボックスモデルでも使える関数単位のfeedbackを優先する。
2. 生成ソースとホストpolicyを分離し、unsafe/解除権限の自己付与を禁止する。
3. JVMのFFIを信頼基盤として明示し、型保証の外側にあるnative/動的ロードを隠さない。
4. 外部操作のUnknown状態・復旧をモデル化し、構造化キャンセルと補償処理を混同しない。
5. 観測データからの修正を支援しつつ、OTelだけで完全なリプレイや監査証明が得られるとはしない。

詳細な試験方法は[VALIDATION.md](VALIDATION.md)。研究由来の改善率・成功率をNGJの既存性能として記載しない。
