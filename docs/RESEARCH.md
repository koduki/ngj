# Research basis and design decisions

調査基準日: 2026-11-17。以下はNGJの設計判断に利用した一次資料。既存言語での実験や形式モデルをNGJの実証結果とみなさない。

| 資料 | 公開情報・位置づけ | NGJで採用する部分 | 適用の限界 |
|---|---|---|---|
| [Generative Compilation](https://arxiv.org/abs/2607.13921) | 2026-07、プレプリント | 生成途中のcompiler feedback | NGJではまず明示的hole。任意prefixの健全性は別に検証 |
| [Language-Based Agent Control](https://arxiv.org/abs/2605.12863v3) | 2026-11-07改訂、プレプリント | Effect/Capability、不透明型、情報フロー | Haskellの実装/保証はJVM境界に自動継承されない |
| [ETAS](https://arxiv.org/abs/2607.17780) | 2026-07、研究プロトタイプ | 副作用と操作履歴、承認/権限の分離 | エージェントDSL全体や任意handlerは採用しない |
| [Type-Constrained Code Generation](https://doi.org/10.1145/3729274) | PLDI 2025 | 型制約を生成側へ公開 | token-level constrained decodingは任意拡張 |
| [Statically Contextualizing LLMs with Typed Holes](https://doi.org/10.1145/3689728) | OOPSLA 2024、[著者版](https://arxiv.org/abs/2409.00921) | 型付き穴、関連APIの取得、LSPとの接続 | 新言語の学習データ不足を完全に解決するとは限らない |
| [Djinnlang](https://people.seas.harvard.edu/~chong/pubs/vericodegen2026djinnlang.pdf) | 2026年公開、AI for Verifiable Coding workshop表記の論文 | 固定した契約と実装/証明の分離 | 全プログラムの仕様一意性や自動証明は必須にしない |

ETASの研究段階については[プロジェクトの説明](https://github.com/etas-project/etas)と[APLAS/ATVA 2026 NIERプログラム](https://conf.researchr.org/details/aplas-atva-2026/aplas-atva-2026-aplas-nier/3/Etas-An-Effect-Typed-Language-for-Agent-Systems)も参照。基準日時点で予定された発表を、完了済みの評価・実用化と扱わない。

## JVM側の設計補正

- [Oracle JDK 24 migration guide](https://docs.oracle.com/en/java/javase/24/migrate/significant-changes-jdk-24.html) / [JEP 491](https://openjdk.org/jeps/491): synchronizedによる仮想スレッドpinningの改善。synchronized一律拒否から、対象JDKと実際の危険操作を分ける設計へ変更。
- [OpenJDK Valhalla value objects](https://github.com/openjdk/valhalla-docs/blob/main/site/value-objects.md): value semanticsとflattening/scalarizationの最適化を区別。NGJではVM buildを固定し、無割当・固定レイアウトの無条件保証をしない。

## 研究からの推論とNGJ固有の選択

論文が直接示すのは各研究の対象言語・課題・モデルでの性質であり、本仕様は以下をNGJ側の設計判断として追加する。

1. CLIとLSPで同じ検査器を利用し、ブラックボックスモデルでも使える関数単位のfeedbackを優先する。
2. 生成ソースとホストpolicyを分離し、unsafe/解除権限の自己付与を禁止する。
3. JVMのFFIを信頼基盤として明示し、型保証の外側にあるnative/動的ロードを隠さない。
4. 外部操作のUnknown状態・復旧をモデル化し、構造化キャンセルと補償処理を混同しない。
5. 観測データからの修正を支援しつつ、OTelだけで完全なリプレイや監査証明が得られるとはしない。

詳細な試験方法は[VALIDATION.md](VALIDATION.md)。研究由来の改善率・成功率をNGJの既存性能として記載しない。
