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

NGJはProject Valhallaのvalue classesおよび厳格な初期化検証を言語設計の基盤とする。以下は設計判断に利用した一次資料。

| 資料 | 公開情報・位置づけ | NGJでの採用 | 適用の限界 |
|---|---|---|---|
| [JEP 401: Value Objects (Preview)](https://openjdk.org/jeps/401) | JDK 28統合済み、2026-08更新 | value classの意味論・構文・JVM表現 | Preview段階。将来変更の可能性 |
| [JEP 539: Strict Field Initialization in the JVM (Preview)](https://openjdk.org/jeps/539) | JDK 28統合済み（JEP 401と同時統合） | `ACC_STRICT_INIT`による未初期化(`0`/`null`)読取防止、deep reflection/`ObjectInputStream`での不変`final`改変防御 | Preview段階。従来のJava直列化とは非互換 |
| [JEP draft 8316779: Null-Restricted Value Class Types](https://openjdk.org/jeps/8316779) | Draft、2026-05更新 | Java境界でのnull制限の参考 | ドラフト段階。NGJはOption優先 |
| [JEP draft 8303099: Null-Restricted and Nullable Types](https://openjdk.org/jeps/8303099) | Draft、2025-07更新 | `T!`/`T?`構文の参考 | ドラフト段階 |
| [JEP 402: Enhanced Primitive Boxing (Draft)](https://openjdk.org/projects/valhalla/) | Draft | primitive統一の参考 | ドラフト段階 |
| [Project Valhalla: Value Objects](https://openjdk.org/projects/valhalla/value-objects) | 公式ドキュメント | value classの設計思想 | JDK 28時点の状態 |
| [Parametric VM design notes](https://openjdk.org/projects/valhalla/design-notes/parametric-vm/parametric-vm) | 設計ノート、研究段階 | ジェネリック特殊化の将来計画 | 研究段階。出荷時期未定 |
| [valhalla-dev ML: Value objects in 28 EA](https://mail.openjdk.org/archives/list/valhalla-dev@openjdk.org/message/7CUEBH4ZEQFCE4WUELGZP4GFABER4XMG/) | 2026-08-10 | JDK 28統合の公式アナウンス | — |

**JDK 28での状態（2026年11月時点）:**
- JEP 401 Value Objects & JEP 539 Strict Field Initialization: Preview統合済み。`--enable-preview`必須
- JDK標準クラスのvalue class化: `Integer`, `Long`, `Optional`, `LocalDate`等30クラス
- `==`の意味論変更: value objectはフィールド値比較
- GA予定: 2027年3月9日

**未出荷機能:**
- Null-Restricted Types: ドラフト
- Enhanced Primitive Boxing: ドラフト
- Parametric JVM（ジェネリック特殊化）: 研究段階

## Integrity by Default・ネイティブ境界・観測性関連

| 資料 | 公開情報・位置づけ | NGJでの採用 | 適用の限界 |
|---|---|---|---|
| [JEP draft 8305968: Integrity by Default](https://openjdk.org/jeps/8305968) | OpenJDK基本方針（Draft） | `restricted`ビルド・信頼境界の基本哲学（配備者による明示許可制） | プロセス内の任意のJNI/OS資源までは隔離不能 |
| [JEP 451: Disallow Dynamic Loading of Agents](https://openjdk.org/jeps/451) / [JEP 486: Disable Security Manager](https://openjdk.org/jeps/486) | JDK 21 / JDK 24 確定 | Security Manager廃止後のJPMS強カプセル化・静的計装前提 | 起動引数でオプトインされたエージェントは許可される |
| [JEP 471 / 498: Unsafe Memory-Access Deprecation & Warning](https://openjdk.org/jeps/498) | JDK 23 / JDK 24 確定 | `sun.misc.Unsafe`依存の排除とBytecode走査での拒否 | 外部JARに一時残存する場合は警告/隔離対象 |
| [JEP 472: Prepare to Restrict the Use of JNI](https://openjdk.org/jeps/472) | JDK 24 確定 | JNI原則禁止・`--enable-native-access`許可制への統一 | 既存Java依存がJNIを持つ場合は境界外として扱う |
| [JEP 500: Prepare to Make Final Mean Final](https://openjdk.org/jeps/500) | JDK 26 確定 | `restricted`プロファイルでの`--illegal-final-field-mutation=deny`および`jdk.FinalFieldMutation` JFR監査 | JEP 539非適用クラスでは起動フラグ依存 |
| [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454) / [JEP 537: Vector API](https://openjdk.org/jeps/537) | FFM: JDK 22確定（JDK 28で確保高速化）<br>Vector: JDK 27 第12 Incubator | `Arena`/`MemorySegment`による`Bytes`/`Slice`寿命・境界保護、将来のSIMD計算候補 | Vector APIはValhalla+総称特殊化待ちでIncubator中 |
| [JEP 520: JFR Method Timing & Tracing](https://openjdk.org/jeps/520) / [JEP 536: JFR In-Process Data Redaction](https://openjdk.org/jeps/536) | JDK 25 / JDK 27 確定 | 実行時bytecode改変不要の計測と、プロセス内JFR秘匿情報マスク | 業務ペイロードのマスキングは引き続きNGJ `Secret<T>`の責務 |

## Project Loom・ランタイムプリミティブ関連

| 資料 | 公開情報・位置づけ | NGJでの採用 | 適用の限界 |
|---|---|---|---|
| [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491) | JDK 24 確定 | `synchronized`の一律拒否を撤回し、native pinning/割り込み非対応のみ評価 | native呼び出しやライブラリ内部競合は別途注意 |
| [JEP 506: Scoped Values](https://openjdk.org/jeps/506) | JDK 25 LTS 確定 | OTel文脈、ホストポリシー、Capability/監査ID、Replay境界の不変伝播基盤 | 外部の独自Java Executorへは明示アダプターが必要 |
| [JEP 543: Structured Concurrency](https://openjdk.org/jeps/543) | JDK 28 確定（[JEP 505](https://openjdk.org/jeps/505)/[533](https://openjdk.org/jeps/533)の`Joiner`合成設計を反映） | `scope`/`fork`/`join`の直接バックエンド。専用`Joiner`でTask Faultと`Result.Err`を分離 | 協調的キャンセルであり、割り込み非対応呼び出しの強制終了は不可 |
| [JEP 513: Flexible Constructor Bodies](https://openjdk.org/jeps/513) | JDK 25 LTS 確定 | `super()`/`this()`前の検証ロジックとJEP 539厳格初期化の両立 | — |
| [JEP 534: Compact Object Headers by Default](https://openjdk.org/jeps/534) | JDK 27 確定（[JEP 519](https://openjdk.org/jeps/519)から昇格、Project Lilliput） | 再帰`enum`・`valhalla-compat`のメモリ削減、22bitクラスポインタ上限（約400万クラス）と特殊化budget整合 | クラス数上限超過時はOOMのため動的クラス生成を厳禁 |
| [JEP 547: Lazy Constants (Fourth Preview)](https://openjdk.org/jeps/547) | JDK 28 Preview候補（旧Stable Values [JEP 502](https://openjdk.org/jeps/502)） | 最多1回初期化の不変遅延定数・JIT定数畳み込み基盤の参考 | Preview段階 |

## AOT/Native Image関連

| 資料 | 公開情報・位置づけ | NGJでの採用 | 適用の限界 |
|---|---|---|---|
| [JEP 483](https://openjdk.org/jeps/483) / [514](https://openjdk.org/jeps/514) / [515](https://openjdk.org/jeps/515) / [516](https://openjdk.org/jeps/516) / [544](https://openjdk.org/jeps/544) (Project Leyden) | JDK 24〜26 確定、JEP 544はJDK 28完了 | OpenJDK標準のAOT本線（クラスロード・プロファイル・任意GCオブジェクト・AOTコードキャッシュ） | JEP 544 × Valhalla（JEP 401/539）の組み合わせはEA検証段階 |
| [Detaching GraalVM from the Java Ecosystem Train](https://blogs.oracle.com/java/detaching-graalvm-from-the-java-ecosystem-train) | Oracle公式発表、2025-09-15（2026年春のProject Galahad/Metropolis解散の契機） | AOT本線をGraalVMからProject Leydenへ移し、Native Imageを外部任意ターゲットに位置づけ | 独立系GraalVM追従の保守コストは別途発生 |
| [Graal on the Road to Valhalla](https://epub.jku.at/download/pdf/11912841.pdf) | 学術論文 | Graal JITのValhalla対応状況 | Native Image（SubstrateVM）はvalue class未対応 |
| [JVM Weekly vol. 180](https://www.jvm-weekly.com/p/project-valhalla-explained-how-a) | 2026解説記事 | Valhalla/特殊化の現状整理 | ジャーナリズム |

**AOT互換性の現状:**
- Project Leyden: JDK 25/26上の`valhalla-compat`ではJEP 514/515/516が利用可能。JDK 28 JEP 544とValhallaの結合は要検証
- GraalVM Native Image: 外部ツールチェーン扱い。SubstrateVMはvalue class未対応のため`valhalla-compat`出力を使用
- 詳細は[AOT設計方向](AOT.md)を参照

## 今後の注目プロジェクト・言語設計の参考

| 資料 | 公開情報・位置づけ | NGJでの参考点・示唆 | 適用の限界 |
|---|---|---|---|
| [JEP 484: Class-File API](https://openjdk.org/jeps/484) / [JEP 485: Stream Gatherers](https://openjdk.org/jeps/485) / [JEP 540: Simple JSON API](https://openjdk.org/jeps/540) | 484/485: JDK 24確定<br>540: JDK 28 Incubator | 484はコンパイラ出力で採用済み。485は型付きトラバーサル設計、540は非リフレクション軽量JSON基盤の候補 | 540はIncubator段階であり、スキーマ検証はNGJ境界側の責務 |
| [Withdrawing the Primitive Patterns Preview Feature](https://mail.openjdk.org/archives/list/amber-spec-experts@openjdk.org/thread/UYEK3PGOHN6R56AHJTSLPGAY363K666S/) | 2026-11-09（JDK 28から撤回） | Valhallaのユーザー定義数値型と暗黙・厳密数値変換が衝突するため撤回。NGJの**暗黙数値拡大変換禁止**設計の裏付け | — |
| [Project Babylon (Code Reflection)](https://openjdk.org/projects/babylon/) | OpenJDK研究（JEP draft 8361105 / HAT） | 型付きシンボリックコードモデルを`.class`へ保持し検証・GPU/SQL/MLへ変換する構想（契約`requires`/`ensures`の将来参考） | 研究・初期段階（post-v0.3候補） |
| [Project Detroit](https://mail.openjdk.org/pipermail/announce/2026-February/000364.html) / [detroit-python](https://github.com/openjdk/detroit-python) | 2026年3月OpenJDKプロジェクト化 | FFM経由でのCPython/V8埋め込みパターン（ポリグロット・アダプターの隔離参考） | NGJコアには採用しない |

## 研究からの推論とNGJ固有の選択

論文やOpenJDK仕様が直接示すのは各対象システムでの性質であり、本仕様は以下をNGJ側の設計判断として追加する。

1. CLIとLSPで同じ検査器を利用し、ブラックボックスモデルでも使える関数単位のfeedbackを優先する。
2. 生成ソースとホストpolicyを分離し、Integrity by Defaultと整合させてunsafe/解除権限の自己付与を禁止する。
3. JVMのFFMを信頼基盤として明示し（JNI原則禁止）、型保証の外側にあるnative/動的ロードを隠さない。
4. 外部操作のUnknown状態・復旧をモデル化し、構造化キャンセル（`StructuredTaskScope`）と補償処理を混同しない。
5. 観測データからの修正を支援しつつ、OTel/JFRだけで完全なリプレイや監査証明が得られるとはしない。

詳細な試験方法は[VALIDATION.md](VALIDATION.md)。研究由来の改善率・成功率をNGJの既存性能として記載しない。
