# Agent-facing compiler protocol — draft v0.3

本仕様は[言語仕様](SPECIFICATION.md)の一部。以下は設計契約である。コンパイラはv0.3正規構文およびCLIプロトコル（`check`/`context`/`inspect`/`build`）を実装している。MCPはこのAPI上の任意のadapterとし、コンパイラがネットワークサービスへ依存しないようにする。

## 1. 部分プログラムとTyped Holes（コード穴・証明穴）

`?name`は名前付きの未実装式（コード穴）、`?proof_name`は`proof { ... }`や`lemma`内に置く証明義務の穴（証明穴）である。穴IDはpackage/symbol/source revision/局所IDに束縛する。周囲の署名やローカル束縛型から期待型、許可Effect、利用可能なCapability（`caps`）/分離状態、契約・目標述語を求める。

- コード穴（`?name`）を含む関数の検査結果は`incomplete`であり、`valid`ではない。未解決の型制約やEffectをpure/安全と仮定してはならない。穴が`consume`義務を果たすか不明な場合、その後の使用は「証明待ち」として示す。すでに確定した矛盾と、補完すれば解消し得る未解決制約を区別する。
- 証明穴（`?proof_name`）のみを含む関数は`incomplete_proof`とし、実行コード自体はテスト実行可能とするが、`proof-required`ビルドおよび`release`ビルドではexit 2で拒否する。これにより実装を固定したまま証明のみを分割・修復できる。

MVPは明示的穴を含む文法的に有効なソースを検査する。壊れた構文の回復はbest effortであり、原文の欠落範囲と回復内容を返す。後段の任意prefix検査は、完成可能なprefixを誤って拒否しない性質を別途検証する。有限時間の検査で「完成可能」を一般に決定できるとは主張しない。

release buildは全穴を拒否する。MVPでは穴を含む成果物の実行を禁止する。独立した完全関数だけをテストする機能は、依存closureにコード穴がないと確認できる場合に限る。

## 2. CLI契約

```text
ngjc check --format=json --diagnostic-detail=agent --profile=restricted --revision=<hash> <files>
ngjc context --format=json --hole=<id> --revision=<hash> --max-bytes=16000
ngjc context --format=json --proof-hole=<id> --revision=<hash> --max-bytes=16000
ngjc inspect --format=json --symbol=<id> --revision=<hash>
ngjc inspect --protocol=<id> --format=mermaid --revision=<hash>
ngjc obligations --format=json --revision=<hash> <files>
ngjc check-patch --format=json --policy=strict-gates --base-revision=<hash> --patch=<file>
ngjc certify-spec --format=json --symbol=<id> --revision=<hash>
ngjc build --target=valhalla --profile=restricted
```

全応答に`schema_version`, `compiler_version`, `source_revision`, `policy_digest`, `target_profile`を含む。revisionはソースと依存lockの内容digestから生成する。診断の再利用時はpolicy digestも一致させる。JSONはstdout、進捗はstderr。通常診断でstack traceや環境変数を漏らさない。

| exit | 意味 |
|---|---|
| 0 | 指定操作が完了。checkなら`valid`（strict-gates時は全機械ゲート通過） |
| 1 | 確定したエラー、または機械ゲート違反（`spec-drift`/`axiom-drift`/`forbidden-construct`等）があり`invalid` |
| 2 | 穴/未解決義務があり`incomplete`または`incomplete_proof`（確定エラーなし） |
| 3 | revision不一致または入力/schema不正 |
| 4 | コンパイラ内部失敗。検査合格ではない |

context/inspect/obligationsは正常な情報取得をexit 0とする。buildのincompleteもexit 2で失敗する。複数診断がある場合invalidをincompleteより優先する。未対応機能はinvalidの`unsupported_feature`とし、成功にしない。

## 3. 構造化診断と二層詳細モード（`human` / `agent`）

`--diagnostic-detail=human|agent`により、人間向けの簡潔な一次原因表示（既定）と、AIエージェント向けの完全な導出・証明コンテキスト出力（`agent`）を切り替える。例はwire formatのサンプルであり、存在するソースの診断結果ではない。

```json
{
  "schema_version": "ngj.diagnostics/2",
  "compiler_version": "0.3.0-valhalla",
  "source_revision": "sha256:example",
  "policy_digest": "sha256:policy-example",
  "target_profile": "valhalla/restricted",
  "diagnostic_detail": "agent",
  "status": "invalid",
  "diagnostics": [{
    "id": "NGJ-EFFECT-001",
    "severity": "error",
    "certainty": "definite",
    "message": "DbWrite exceeds the declared effect bound",
    "primary_span": {"file": "src/service.ngj", "start_byte": 120, "end_byte": 145},
    "expr_snippet": "db.insert(row)",
    "symbol_id": "app.service.findUser",
    "expected": {"effects": ["DbRead"]},
    "actual": {"effects": ["DbRead", "DbWrite"]},
    "derivation_trace": [
      {"step": 1, "span": {"start_byte": 120, "end_byte": 145}, "expr_snippet": "db.insert(row)", "reason": "requires effect DbWrite"}
    ],
    "related": [{"kind": "callee", "symbol_id": "app.db.insert"}],
    "obligation_id": "effect-bound:app.service.findUser",
    "suggestions": [{
      "kind": "review_call",
      "message": "Use an operation within the existing authority boundary",
      "applicability": "requires_review"
    }]
  }]
}
```

位置はUTF-8 byte offset、半開区間。LSP adapterはUTF-16等の位置encodingへ明示変換する。LLMは行・列計算を誤りやすいため、`agent`モードでは`primary_span`に加え該当式の正規化テキスト`expr_snippet`および完全な制約導出スタック`derivation_trace`を必ず同梱する。diagnostic IDはversion間で安定させ、messageの自然言語に依存する機械処理を要求しない。一次原因を優先し、派生エラーを関連情報にまとめる。

Capture/Separation診断では`read`/`update`/`consume`/寿命境界の発生位置を返す。契約・SMT診断ではSurface A/B/Cの検証状態に加え、`solver_diagnosis`として`failure_class`（`counterexample_found` / `quantifier_instantiation_gap` / `axiomatization_gap` / `loop_invariant_missing` / `timeout`）、欠落ヒント挿入候補位置`suggested_hint_anchor`、および`Secret`マスク済みの具体的反例状態`counterexample_state`を返す。機密文字列をidentifier/message/fixへ埋め込まない。

自動修正は型エラーを消すために権限拡大、Secret解除、契約削除、検証スキップ属性や無検査キャストの追加を行ってはならない。そのような変更候補は`requires_review`で分類し、ホストpolicy上で拒否され得る。テキスト編集を返す場合はbase revisionと適用範囲を必須とする。

## 4. 型・補題に基づくcontext取得

以下のBytes/CaptureのJSON例はPhase 2以降のCapability・篩型を有効にしたプロファイルの契約である。最初のpure core profileで対応済みとは扱わない。

```json
{
  "schema_version": "ngj.context/2",
  "compiler_version": "0.3.0-valhalla",
  "source_revision": "sha256:example",
  "policy_digest": "sha256:policy-example",
  "target_profile": "valhalla/restricted",
  "hole_id": "net.protocol.parseFrames:body",
  "expected_type": "Result<List<PacketFrame<caps(raw)>>, ParseError>",
  "allowed_effects": [],
  "bindings": [{"name": "raw", "type": "Bytes<caps(arena)>", "capability_mode": "read"}],
  "relevant_symbols": [{
    "id": "std.io.Bytes.slice",
    "signature": "fn slice(raw: read Bytes<caps(a)>, offset: Long[0L..raw.len()], length: Long[0L..raw.len() - offset]) -> Bytes<caps(a)> effects()",
    "origin": "stdlib",
    "content_digest": "sha256:api-example"
  }],
  "obligations": [{"kind": "capture_lifetime", "description": "returned views cannot outlive arena"}],
  "truncated": false,
  "omitted_symbol_count": 0
}
```

`ngjc context --proof-hole=<id>`では、目標述語に含まれる型・関数シンボルから利用可能な`spec fn`および証明済み`lemma`の署名・前提・事後条件を同じbyte budget内で返す。

検索順は期待型・ローカルbindingからの型依存、利用可能なconstructor/関数、契約・補題、関連する使用例。tokenizer依存のtoken数ではなくbyte budgetを基準とし、token推定は任意metadataとする。循環参照はsymbol IDで打ち切る。省略を明記し、全文取得のIDを返す。

存在しないAPIを生成して提示しない。source revision、依存version、可視性に合う宣言のみを返す。コメント・例・外部文書は`untrusted_documentation`として型checker由来の事実と区別する。コンパイラ出力を介したprompt injectionを命令として扱わない。

## 5. シンボル変更・Trust-First機械ゲート・仕様強度認証

`check-patch`は適用前revisionを照合し、仮想workspaceで構文/型/Effect/Capture-Separation/篩型/契約を再検査する。元のworkspaceへの書込みは行わない。API差分には型、Effect上限、Capabilityモード（`read`/`update`/`consume`）、公開契約、許可adapterの変更を含める。

`--policy=strict-gates`指定時は、以下の**Trust-First機械ゲート**をすべて満たさない限りexit 1でパッチを拒否する：
- **`spec-drift`**: ホストが凍結した公開契約・`spec fn`/`spec predicate`・`protocol`定義のAST正規化ハッシュ改変を拒否（`NGJ-GATE-SPEC-DRIFT`）。
- **`axiom-drift`**: 許可リスト外の`@trusted_pure` / `unsafe java_interop` / 新規外部公理追加を拒否（`NGJ-GATE-AXIOM-DRIFT`）。
- **`forbidden-construct`**: `proof-required`ビルドでの`assume`・検証スキップ属性・無検査キャスト相当の混入を拒否（`NGJ-GATE-FORBIDDEN`）。
- **`sibling-check`**: 変更パッチが依存関係グラフ上の呼び出し元・兄弟モジュールの型・証明を破壊していないことを検証（`NGJ-GATE-SIBLING`）。
- **`obligation-monotonicity`**: `ngjc obligations`で集計する未解決穴・未証明義務が増加していないか、完了報告時に0であることを検証。

`ngjc certify-spec`は対象シンボルの検証品質をSurface A（SMT受理）/ Surface B（事前条件の非空虚性・凍結`spec`への`ensures`拘束）/ Surface C（`Post2Exe`による負例テスト拒否率・ミューテーション＋PBTによる強仕様/弱仕様証人生成）の三層JSONとして出力する。

変更関数だけでなく、依存する呼び出し元、再帰成分、derive、公開ABIを無効化する。cache keyにcompiler version、target、source、依存lock、policyを含める。診断の同一性はincremental/full checkで比較する。シンボルIDによる置換はテキスト位置ずれを減らすが、型検査を省略する理由にはしない。

## 6. IDE/LSPと生成ループ

通常LSPのdiagnostics、definition、referencesを提供し、context/inspect/obligations/check-patch/certify-specを拡張メソッドで公開する。CLIとLSPは同じ検査エンジンを使う。特定のモデルやtoken-level APIを必須にしない。

推奨ループ: 凍結仕様（`spec`/`protocol`/署名・契約）の確定＋`certify-spec`による弱仕様検査 → 穴の`context`取得 → 関数本体・`proof`ヒントを生成 → `check --diagnostic-detail=agent`による部分検査 → 反例・`Post2Exe`・PBT → `check-patch --policy=strict-gates`による全体検査。モデルが生成した契約と実装だけで合否を自己判定させない。制約付きデコーディングは後段の任意clientとして扱う。

## 7. v0.3構文と診断境界

[EBNF.md](EBNF.md)の正規構文だけをcontextの生成例に使用する。旧構文の混在は移行診断（`NGJ-MIGRATE-*`）で示す。Result伝播先のfunction/lambda/fork境界IDを保持し、for callback内の`?`および`return`は明示的に拒否する（`NGJ-PROP-002`）。関数の`return`漏れ（`NGJ-RETURN-001`）、非`Unit`式文による戻り値消失（`NGJ-TYPE-016`）、`Option`への`?`（`NGJ-PROP-001`）、constructorの括弧不足は原因に即した診断を返す。全関数の型・Effectは明示署名から取得する。
