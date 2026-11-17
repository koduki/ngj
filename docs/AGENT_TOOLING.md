# Agent-facing compiler protocol — draft v0.3

本仕様は[言語仕様](SPECIFICATION.md)の一部。以下は設計契約である。既存compilerにはCLI実装があるが、v0.3構文・全プロトコルの適合確認は移行試験で行う。MCPはこのAPI上の任意のadapterとし、コンパイラがネットワークサービスへ依存しないようにする。

## 1. 部分プログラムとTyped Holes

`?name`は名前付きの未実装式。穴IDはpackage/symbol/source revision/局所IDに束縛する。周囲の署名から期待型、許可Effect、利用可能な値、所有権状態、契約を求める。

穴を含む関数の検査結果は`incomplete`であり、`valid`ではない。未解決の型制約やEffectをpure/安全と仮定してはならない。穴がresourceを消費するか不明な場合、その後の使用は「証明待ち」として示す。すでに確定した矛盾と、補完すれば解消し得る未解決制約を区別する。

MVPは明示的穴を含む文法的に有効なソースを検査する。壊れた構文の回復はbest effortであり、原文の欠落範囲と回復内容を返す。後段の任意prefix検査は、完成可能なprefixを誤って拒否しない性質を別途検証する。有限時間の検査で「完成可能」を一般に決定できるとは主張しない。

release buildは全穴を拒否する。MVPでは穴を含む成果物の実行を禁止する。独立した完全関数だけをテストする機能は、依存closureに穴がないと確認できる場合に限る。

## 2. CLI契約

```text
ngjc check --format=json --profile=restricted --revision=<hash> <files>
ngjc context --format=json --hole=<id> --revision=<hash> --max-bytes=16000
ngjc inspect --format=json --symbol=<id> --revision=<hash>
ngjc check-patch --format=json --base-revision=<hash> --patch=<file>
ngjc build --target=valhalla --profile=restricted
```

全応答に`schema_version`, `compiler_version`, `source_revision`, `policy_digest`, `target_profile`を含む。revisionはソースと依存lockの内容digestから生成する。診断の再利用時はpolicy digestも一致させる。JSONはstdout、進捗はstderr。通常診断でstack traceや環境変数を漏らさない。

| exit | 意味 |
|---|---|
| 0 | 指定操作が完了。checkなら`valid` |
| 1 | 確定したエラーがあり`invalid` |
| 2 | 穴/未解決義務があり`incomplete`（確定エラーなし） |
| 3 | revision不一致または入力/schema不正 |
| 4 | コンパイラ内部失敗。検査合格ではない |

context/inspectは正常な情報取得をexit 0とする。buildのincompleteもexit 2で失敗する。複数診断がある場合invalidをincompleteより優先する。未対応機能はinvalidの`unsupported_feature`とし、成功にしない。

## 3. 構造化診断

例はwire formatのサンプルであり、存在するソースの診断結果ではない。

```json
{
  "schema_version": "ngj.cli/1",
  "compiler_version": "design-v0.3",
  "source_revision": "sha256:example",
  "policy_digest": "sha256:policy-example",
  "target_profile": "valhalla/jdk28-ea-b24",
  "status": "invalid",
  "diagnostics": [{
    "id": "NGJ-EFFECT-001",
    "severity": "error",
    "certainty": "definite",
    "message": "DbWrite exceeds the declared effect bound",
    "primary_span": {"file": "src/service.ngj", "start_byte": 120, "end_byte": 145},
    "symbol_id": "app.service.findUser",
    "expected": {"effects": ["DbRead"]},
    "actual": {"effects": ["DbRead", "DbWrite"]},
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

位置はUTF-8 byte offset、半開区間。LSP adapterはUTF-16等の位置encodingへ明示変換する。diagnostic IDはversion間で安定させ、messageの自然言語に依存する機械処理を要求しない。一次原因を優先し、派生エラーを関連情報にまとめる。

ownership診断ではmove/borrow/freezeの発生位置と寿命境界を返す。契約診断では義務・前提・証明状態を返す。反例値は秘密ラベルに従って除去する。機密文字列をidentifier/message/fixへ埋め込まない。

自動修正は型エラーを消すために権限拡大、Secret解除、契約削除、unsafe追加を行ってはならない。そのような変更候補は`requires_review`で分類し、ホストpolicy上で拒否され得る。テキスト編集を返す場合はbase revisionと適用範囲を必須とする。

## 4. 型に基づくcontext取得

以下のBytes/借用のJSON例は所有権機能を有効にした後続profileの契約である。最初のpure core profileで対応済みとは扱わない。

```json
{
  "schema_version": "ngj.context/1",
  "compiler_version": "design-v0.3",
  "source_revision": "sha256:example",
  "policy_digest": "sha256:policy-example",
  "target_profile": "valhalla/jdk28-ea-b24",
  "hole_id": "net.protocol.parseFrames:body",
  "expected_type": "Result<List<PacketFrame<'a>>, ParseError>",
  "allowed_effects": [],
  "bindings": [{"name": "raw", "type": "Bytes<'a>", "ownership": "shared_borrow"}],
  "relevant_symbols": [{
    "id": "std.io.Bytes.slice",
    "signature": "fn slice(self: &Bytes<'a>, offset: Long, length: Long) -> Result<Bytes<'a>, BoundsError> effects()",
    "origin": "stdlib",
    "content_digest": "sha256:api-example"
  }],
  "obligations": [{"kind": "lifetime", "description": "returned views cannot outlive 'a"}],
  "truncated": false,
  "omitted_symbol_count": 0
}
```

検索順は期待型・ローカルbindingからの型依存、利用可能なconstructor/関数、契約、関連する使用例。tokenizer依存のtoken数ではなくbyte budgetを基準とし、token推定は任意metadataとする。循環参照はsymbol IDで打ち切る。省略を明記し、全文取得のIDを返す。

存在しないAPIを生成して提示しない。source revision、依存version、可視性に合う宣言のみを返す。コメント・例・外部文書は`untrusted_documentation`として型checker由来の事実と区別する。コンパイラ出力を介したprompt injectionを命令として扱わない。

## 5. シンボル変更とincremental check

`check-patch`は適用前revisionを照合し、仮想workspaceで構文/型/Effect/所有権/契約を再検査する。元のworkspaceへの書込みは行わない。API差分には型、Effect上限、所有権、公開契約、許可adapterの変更を含める。

変更関数だけでなく、依存する呼び出し元、再帰成分、derive、公開ABIを無効化する。cache keyにcompiler version、target、source、依存lock、policyを含める。診断の同一性はincremental/full checkで比較する。シンボルIDによる置換はテキスト位置ずれを減らすが、型検査を省略する理由にはしない。

## 6. IDE/LSPと生成ループ

通常LSPのdiagnostics、definition、referencesを提供し、context/inspect/check-patchを拡張メソッドで公開する。CLIとLSPは同じ検査エンジンを使う。特定のモデルやtoken-level APIを必須にしない。

推奨ループ: 署名・契約の確定 → 穴のcontext取得 → 関数本体を生成 → 部分検査 → 反例/テスト → 全体検査。モデルが生成した契約と実装だけで合否を自己判定させない。制約付きデコーディングは後段の任意clientとして扱う。

## 7. v0.3構文と診断境界

[EBNF.md](EBNF.md)の正規構文だけをcontextの生成例に使用する。旧構文の混在は移行診断で示す。Result伝播先のfunction/lambda/fork境界IDを保持し、for callback内の?は明示的に拒否する。末尾セミコロンによる戻り値消失、Optionへの?、constructorの括弧不足は原因に即した診断を返す。全関数の型・Effectは明示署名から取得する。
