# Validation and implementation plan

現時点ではコンパイラ・テストハーネスは存在しない。以下は実装の受入条件であり、試験済みの結果ではない。[仕様](SPECIFICATION.md)と[ツールAPI](AGENT_TOOLING.md)に対する適合性を測る。

## 1. 機能ごとの受入条件

各positive/negative caseを独立したfixtureにし、negativeは指定の診断IDで失敗することを検証する。未実装機能のskipを成功件数へ含めない。

| ID | 正常系 | 拒否・異常系 | 段階 |
|---|---|---|---|
| CORE-01 | enumの網羅match、Result伝播 | 非網羅match、異なるEの暗黙伝播 | 1 |
| CORE-02 | checkedAddによる回復 | 通常加算のoverflowはfault | 1 |
| HOLE-01 | 穴の期待型/文脈を取得 | 穴ありrelease buildを拒否 | 1 |
| HOLE-02 | 未解決と確定矛盾を区別 | 穴のEffectをpureと誤認しない | 1 |
| API-01 | JSON解析、UTF-8位置を復元 | stale revision、未対応schemaを拒否 | 1 |
| API-02 | incremental/fullの診断一致 | 依存署名変更時の古いcacheを使用しない | 1 |
| EFF-01 | DbReadのみの呼び出し | DbRead関数からDbWrite、fork/closure経由も拒否 | 2 |
| CAP-01 | 対象を狭めた委譲 | 偽造、権限拡大、失効、symlink/redirectの迂回 | 2 |
| OWN-01 | 一意builderのfreeze | 可変別名escape、move後利用 | 2 |
| OWN-02 | 所有者存続中のslice | Arena close後利用、thread-confined viewのfork | 2 |
| OWN-03 | 範囲内slice | 負長、過大長、offset+length overflow | 2 |
| FFI-01 | nullable/例外の明示mapping | 不明メソッドをpure扱い、偽のaot_verified | 2 |
| FFI-02 | hash固定adapter | adapter改変、推移依存差替え、unsafe迂回 | 2 |
| CONC-01 | 正常な全子join | 孤児task、scope外handle、二重join | 3 |
| CONC-02 | 子faultで兄弟cancel | domain Errをtask faultと混同しない | 3 |
| CONC-03 | bounded channelのbackpressure | close/cancel時に送信値を失わない | 3 |
| CLEAN-01 | 早期return時の逆順cleanup | cleanup失敗が主エラーを消さない | 3 |
| STATE-01 | Pendingを一度だけ確定/解放 | 二重commit、未解決の破棄 | 3 |
| STATE-02 | Unknownを復旧ストアへ移譲 | timeoutを「未実行」とみなす再送を拒否/検出 | 3 |
| IFC-01 | Secretを固定マスクで記録 | 変換/例外/反例/JSON経由の明示的漏洩 | 3 |
| IFC-02 | 用途に一致する解除権限 | 無権限expose、任意hashの公開化 | 3 |
| CONTRACT-01 | seed付き反例再現 | solver unknownをproved扱いしない | 3/4 |
| OBS-01 | forkへのcontext継承 | SecretのBaggage/attribute流出 | 3 |
| REPLAY-01 | 記録I/Oの逐次再実行 | 不一致時のlive fallback禁止 | 3 |
| VALUE-01 | data/record/enumがvalue classとして出力 | identity classへの誤出力 | 1 |
| VALUE-02 | `==`がvalue型で値比較 | 参照比較への誤り | 1 |
| VALUE-03 | Int/Long/Boolがvalue型として動作 | boxing/unboxingの露出 | 1 |
| NULL-01 | NGJコードで`null`リテラル使用不可 | nullの受理 | 1 |
| NULL-02 | Java境界nullがOption変換 | nullの透過 | 2 |
| ENUM-01 | ペイロード付きenumバリアント | バリアントフィールドエラー | 1 |
| ENUM-02 | match網羅性検査で欠落バリアント診断 | 欠落を検出しない、診断に名前なし | 1 |
| ENUM-03 | ネストパターンマッチ | ネスト分解エラー | 1 |
| ENUM-04 | Result/Optionが通常enumとして動作 | 特別扱いによる不整合 | 1 |
| GENERIC-01 | `List<Int>`が型検査通過 | value型ジェネリクス拒否 | 1 |
| TARGET-01 | backend間で意味論一致 | 未対応preview/ABIを黙ってfallbackしない | 4 |
| TARGET-02 | Valhalla未対応時の明示エラー | 黙ってidentity fallback | 1 |

fault/cancel/cleanup試験はfailure injectionで全退出経路を通す。プロセスクラッシュ時の業務復旧はadapter integration testで別に検証し、言語の静的保証と混ぜない。

## 2. AI生成の比較実験

同じモデルversion、予算、task、依存lock、promptの業務要件を固定し、条件を比較する。

| 条件 | 提供するもの |
|---|---|
| A | 言語マニュアル・API一覧・通常のコンパイラfeedback |
| B | A + 型に基づくcontext取得 |
| C | B + Typed Holes・部分検査・構造化診断 |
| D | C + 契約・反例・property tests |

対象はREST/DB境界、バイナリパーサー、bounded並列CLI、結果不明を含む決済復旧、API変更修正。最低30の独立課題、各条件3回以上を初期pilotとし、信頼区間を添える。必要標本数はpilot分散から再計画する。課題/seed/モデル変更後に同一実験として比較しない。

主要指標はhidden testsを満たす機能正答率。副指標は初回/予算内compile成功率、修正回数、入出力token、壁時計時間、費用、拒否された危険操作、無害なコードの誤拒否率。timeout、unsupported、予算切れ、契約弱化による見かけの成功を独立分類する。

生成対象は実装のみ。policy・adapter allowlist・hidden tests・評価用契約はread-onlyの外部基準に置く。モデルがコンパイルを通すためにEffectを増やしたり検証を消した場合は成功としない。データ/課題の漏洩と学習済み例の再現を避けるため、公開例とは別のholdoutを使う。

Python/Rust/Kotlinとの言語間比較は後段。ライブラリ成熟度と学習データ量が交絡するため、まずNGJ内のablationで機能の効果を評価する。論文の改善率をNGJの目標値や達成値として流用しない。

## 3. 性能と互換性

測定はJDK/GraalVM build、OS/arch、CPU、heap、GC、flags、依存hash、warmup、反復数を記録する。

- `data`/Result/Option/特殊化: allocation bytes/op、throughput、p50/p95/p99、code size。
- build/freeze/slice: payloadコピー量とwrapper割当を分離。永続コレクション更新の割当も測る。
- concurrency: 同時接続、CPU/メモリ、queue待ち、pinning、cancel/cleanup latency。
- instrumentation: 無効/有効/sampling/exporter停止時の差を測る。監査とtelemetryを区別。
- Native Image: build可否、起動、RSS、JFR/OTel、DB/TLS、例外/cleanup、replayのsmoke test。

Java25 JVMを基準としてValhalla/Native Imageを比較し、言語の意味論が同じかも検査する。性能が悪い場合は特殊化heuristic等を変更し、意味論を弱めない。小さいマイクロベンチだけでアプリ全体の無割当や高速性を主張しない。

## 4. Phaseごとの完了条件

1. 最小compilerが正負fixtureを実行でき、hole/JSON/contextを提供する。全exampleのアプリstubと標準APIを整備して構文・型の適合確認を可能にする。
2. Effect/ownership/adapterの検査を強制し、迂回negative casesが失敗する。restricted profileの信頼基盤を文書化する。
3. scope/cancel/cleanup・外部結果不明・情報フロー・観測性を統合試験する。replayの保証範囲を実測とともに公開する。
4. targetごとの互換性行列、性能、AI生成ablationの生データと再現手順を公開する。証明器の未対応領域は拒否またはruntime checkとして明示する。

## 5. この仕様改訂自体の検証

改訂時にはMarkdownの相対リンク、JSONサンプルのparse、code fence、用語/サンプル整合性を確認する。これはNGJのコンパイルや意味論の証明ではない。compiler導入後には、この文書のfixtureを実際の適合性テストへ移す。
