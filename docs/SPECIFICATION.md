# NGJ — Concept & Language Specification

> Draft v0.2 / 2026-11-17
> Status: language design, not an implemented compiler or a production security guarantee.
> Baseline: Java 25 class files; optional, pinned Valhalla and Native Image backends.

## 1. 目的・適用範囲

NGJは、AIエージェントが生成・修正することを第一級の利用形態とする、静的型付け・式指向のJVM言語である。人間がレビューできるソースコードを保ち、コンパイラが型・所有権・副作用・契約の情報を生成側に返す。Javaのライブラリ資産を境界アダプター経由で利用し、サーバー、CLI、バイナリ処理を主な用途とする。

### 1.1 設計原則

1. **Value first:** 不変の値と、同一性・状態・寿命を持つリソースを分離する。Valhallaを最適化先とするが、意味論はVMの配置方式に依存させない。
2. **Colorless structured concurrency:** `scope` / `fork` と仮想スレッドによって、`async/await`なしの直接的な記述を提供する。
3. **Checkable authority:** 型・Effect・Capabilityで、実行可能な操作と対象を表す。安全性をソース中の自己申告に依存させない。
4. **Compiler as an agent interface:** 型付き穴、部分プログラム検査、構造化診断、意味に基づく文脈取得をコンパイラの公開機能とする。
5. **Observable and verifiable behavior:** OTel/JFR、契約、反例、I/O再実行を接続する。観測と保証を区別する。
6. **Static interop and AOT:** NGJ自身は実行時リフレクションに依存せず、Java境界とバックエンドの要件をビルド時に検査する。

AIを言語ランタイムやコンパイラの必須依存にはしない。同じソース・依存・ツールチェーンからのビルドにLLM呼び出しは不要。エージェント専用構文やプロンプトDSL、任意の依存型、完全な自動証明はv0.2の必須要件ではない。

### 1.2 文書の読み方と保証範囲

本文の「必須」「拒否」は将来実装が満たすべき規範要件。「候補」「後段」は未実装の研究・拡張項目。すべての構文例は提案構文であり、実行確認済みコードではない。

- 本文: 言語・ランタイム・Java境界の規範仕様。
- [AGENT_TOOLING.md](AGENT_TOOLING.md): CLI/診断/部分検査のプロトコル。
- [VALIDATION.md](VALIDATION.md): 適合性試験、性能検証、AI生成評価、実装順。
- [RESEARCH.md](RESEARCH.md): 研究との対応、出典と証拠の限界。
- [examples/README.md](../examples/README.md): サンプルの前提と未定義アプリAPI。

型検査は業務仕様の正しさ、停止性、外部サービスの応答、タイミング側信道を一般には保証しない。安全性は、検査器・標準ランタイム・許可されたアダプター・ホストのポリシーを信頼基盤とする。任意のJava/JNIコードを同一プロセスにロードした場合に、型だけで隔離できるとは主張しない。

## 2. 構文と基本意味論

### 2.1 モジュール・名前解決

- ファイルは`module app.name`で始める。importは明示し、ワイルドカードimportは提供しない。
- 宣言の可視性はデフォルトでmodule内。`pub`だけを外部へ公開する。
- 型名・値名は解決後のシンボルIDで識別する。曖昧なimportはエラーとし、暗黙の候補選択をしない。
- 公開関数は引数型、戻り値型、Effect上限を明記する。ローカル変数・非公開関数のEffectは推論できる。再帰する公開APIは明示署名を要する。
- 暗黙の数値変換、暗黙のnull許容、ユーザー定義の暗黙変換はない。名前付き引数は宣言された引数名と一致させる。
- `@derive(JsonCodec, CliArgs)`等は登録済みの決定的なコンパイル時展開。生成コードも同じ検査を通す。任意のビルドスクリプト実行権限を与えない。

概略構文（完全な字句/EBNF仕様はPhase 1で固定）:

```text
function := [pub] fn name [<type-parameters>] (parameters)
            -> type ! {effect-list} [contract-clauses] block
hole     := ?identifier
propagate := expression ?
borrow   := &expression | &mut expression
```

`?name`は式の開始位置の穴、`expr?`は後置のエラー伝播として区別する。ブロック末尾の式が値。引数・演算子の評価は左から右、`&&` / `||`は短絡評価。`match`は網羅性を検査する。`return`と`?`は包含関数を終了し、その途中のcleanupを実行する。

### 2.2 値・同一性・不透明型

| 宣言 | 意味 | 制約 |
|---|---|---|
| `data` | 不変の積型、構造的等価性 | 全フィールドが不変。生の可変Java参照を格納しない |
| `enum` | 閉じた直和型 | パターン分岐を網羅させる |
| `resource` | 寿命・所有権を持つ同一性オブジェクト | affine、暗黙コピー禁止 |
| `actor` | 隔離した可変状態とメッセージ処理 | 状態への直接外部アクセス禁止 |
| `opaque data` | module外から表現・コンストラクターを隠す値 | 公開された検証済み生成関数だけが構築可能 |

`==`は値の等価性。`===`は同一性のある型でのみ使用できる。`data`内の参照も安全な不変表現でなければならず、`final`フィールドだけでは深い不変性の証拠にならない。

`Copy`は複製しても寿命・権限・消費制約を破らない型の性質、`Send`は所有権を別タスクへ移せる性質、`Share`は共有借用を並行使用できる性質。コンパイラがフィールドから導出し、アプリ側の無検査な宣言は禁止する。`Copy`は物理的なメモリコピーを必須としない。メソッド呼び出しのreceiverは宣言された`self`モードに従って自動借用できるが、通常の関数引数の借用は`&`で明示する。

不透明型の構築権限とCapabilityを合わせることで、`Verified<T, Source>`を外部入力やキャストから偽造できなくする。出典ラベルは出典の事実を表し、データ内容の真実性を証明しない。

### 2.3 数値・失敗・不在

- `Int`は符号付き32bit、`Long`は符号付き64bit、`Byte`は符号なし8bit。通常の整数演算はオーバーフローを検出し、タスクfaultにする。必要な回復は`checkedAdd`等の`Result` APIで記述する。wrap/saturateは明示APIのみ。
- `Result<T,E>`と`Option<T>`を正規の失敗・不在表現とする。v0.1の代替候補`T | E`は採用しない。
- `?`は`Err(e)`を現在の関数へ伝播する。異なるエラー型には明示的な`mapErr`を必要とする。失敗する値の黙殺はエラーとし、意図的な破棄には理由付きの明示操作を要する。
- NGJには`null`リテラルとユーザーの`throw`構文を提供しない。ただしVM障害、契約違反、オーバーフロー、ランタイム不変条件違反は存在する。
- 回復可能なドメインエラーは`Result`、協調キャンセルは`Cancelled`、プログラミング上のfaultはタスク失敗として区別する。OOM等のVM致命障害の回復やcleanup成功は保証しない。

`Result`の値型化は最適化機会であり、全ケースの非ボクシング・無割当を意味しない。

## 3. 所有権・借用・freeze・メモリ

### 3.1 所有権規則

`resource`、builder、タスクハンドル、消費型Capabilityはaffine: move後の元変数の使用を拒否する。分岐の合流点では、全ての到達経路で有効なものだけを使用できる。

- `&T`: 読み取り借用。所有者より長生きできない。
- `&mut T`: 排他的借用。同時に他の借用を持てない。
- 借用中は所有者のmove/close/freezeを拒否する。
- closureへのcaptureにも同じ規則を適用。`fork`はcaptureのSend/Shareと寿命を検査する。
- MVPの借用期間は字句スコープ単位。非字句ライフタイムは診断の改善後に導入できる。

### 3.2 buildと不変コレクション

```rust
let values = build MutableList<Int> {
    this.add(1)
    this.add(2)
    this
}
```

`this`はblock内の一意なbuilder。正常終了時に消費し、不変`List<Int>`へ変換する。別名・可変slice・closureを外へ逃がすことは拒否する。早期return/Err/cancel/faultではbuilderを破棄し、不完全な値を公開しない。

`let mut`によるローカル変数の再束縛は許すが、共有オブジェクトの可変性を意味しない。純粋関数内でも外から観測できないローカル構築は許す。

`List`/`Map`/`Set`は不変APIと構造共有を基本とする。独占しているbacking storageを再利用できるbuilderではゼロコピーfreezeを行う。表現変換が必要な場合のコピー・ノード割当は許容し、性能報告へ記録する。永続データ構造全体の無割当や全操作のロックフリー性は保証しない。

コレクションの所有権性質は要素から導出する。`List<Task<T>>`のようにaffine要素を含むListもaffineであり、共有コピー・永続分岐は禁止。消費iteration/mapは元のcontainerから要素をmoveする。要素がShareの場合だけ構造共有を許す。`List.map`はeagerで、左から右に一度ずつcallbackを実行する。

### 3.3 Bytes・Slice・Arena

- `Bytes<'a>`は、寿命`'a`の不変バイト列を参照するview。`Slice<'a,T>`も同様。寿命が推論可能なら省略できるが、所有する値と混同してはならない。
- 不変view自体は寿命制約を保ったままCopyできるが、Send/Shareはbacking storageの属性に従う。
- `OwnedBytes`は所有する不変バイト列。`asBytes()`のviewは所有者を超えて生存できない。
- slice生成は境界を検査し`Result`を返す。範囲は`offset <= len`かつ`length <= len - offset`で検証し、加算のオーバーフローを避ける。
- viewは新しいpayloadコピーを行わないが、viewオブジェクト自体の割当消去はバックエンド依存。
- `Arena`のメモリをviewが参照している間はcloseを拒否する。`scope`と統合したArenaでは子タスクの終了を待ってからcloseする。
- thread-confined Arenaのviewは`Send`ではない。共有Arenaでも、読み取り専用公開と所有者の寿命を検査する。
- mutableなJava/native側の別名を残したまま不変Bytesへ変換してはならない。コピーするか、信頼された独占所有権移譲を必要とする。
- 外へ返す場合は所有者も安全に移譲するか、`OwnedBytes`へ明示コピーする。寿命消去キャストは禁止。

FFMの実行時境界検査・寿命検査は防御層として維持する。静的に証明できない箇所を無検査に変換しない。

### 3.4 resourceとcleanup

`defer`は取得成功後に登録し、逆順で全ての通常終了・早期終了・キャンセル・fault経路で実行する。登録時にcleanup用の借用または所有権を保持し、解放済みの対象への二重登録を拒否する。

cleanupが失敗可能なら、境界APIは`CleanupFailure`を戻り値のエラーに含める。既存の主エラーがある場合はcleanup失敗を付随情報として保持し、主エラーを上書きしない。失敗しても残りのcleanupを試みる。cleanupはキャンセル保留下で実行するが、外部I/Oには期限を必要とする。

DBのcommit/rollbackや支払い解放を、メモリ解放と同じ暗黙destructorに任せない。業務的な補償は失敗・再試行・結果不明を明示するAPIを持つ。

## 4. EffectとCapability

### 4.1 Effect集合

```rust
pub fn calculateFee(amount: Money) -> Money ! {} { ... }
pub fn findUser(db: &DbRead<Users>, id: UserId)
    -> Result<Option<User>, DbError> ! { DbRead } { ... }
```

`! {}`は外部観測可能な業務副作用なし。時刻、乱数、環境変数、ファイル、ネットワーク、DB、process起動、機密解除はそれぞれEffectとして扱う。例: `Clock`, `Random`, `EnvRead`, `FsRead`, `FsWrite`, `Net`, `DbRead`, `DbWrite`, `PaymentWrite`, `Declassify`。

信頼されたアダプターは複数の下位操作を、対象が限定されたドメインEffectにまとめられる（例: PaymentWrite）。manifestには下位操作・Capability・監査要件を残し、無制限な権限を隠さない。通常のアプリコードにはこのEffect再分類を許さない。

式のEffectは実行され得る子式と呼び出し先Effectの和集合。分岐は両側の和集合。関数本体の推論結果は宣言上限の部分集合でなければならない。上位関数もclosureのEffectを保持する。純粋callbackを要求するAPIへ副作用付きclosureを渡せない。

再利用可能な高階関数にはEffect変数`ε`を用いた有限集合上の多相性を設ける。MVPでは明示的な単一Effect変数と和集合に制限し、handlerによる任意Effect消去は導入しない。`fork`内のEffectも親の推論結果に含め、並列化で権限検査を迂回できない。

`! {}`は停止性や無faultを意味しない。コンパイラ管理の計測は業務Effectと区別するが、公開/マスク済みデータのみを許し、権限や情報フロー検査を迂回しない。再現テストでは無効化または収集先を固定する。

### 4.2 Capabilityとホストポリシー

Effectは「操作の種類」、Capabilityは「どの対象に何を許すか」を表す。`FsRead<Root>`や`DbWrite<Tenant>`等は信頼されたホストだけが発行する不透明値。文字列、JSON、公開コンストラクターから生成できない。

- Capabilityを持つだけでは操作しない。呼び出し側のEffect上限も満たす必要がある。
- Capabilityの複製・共有可否は発行時の型に含める。単回承認はaffine、読み取り権限は必要に応じShare。
- 委譲は同じか狭い権限のみ。対象・期限・回数・操作の拡大は拒否する。
- パスや接続先が動的なら、実行境界でも検査する。パス正規化、symlink、redirect等を含む対象解決をアダプターの責務とし、単なる文字列prefix比較にしない。
- 実行時の失効・期限切れ・ポリシー拒否は型付きエラー。ソースの注釈だけで許可しない。

エージェントが編集するソースと、信頼されたpolicy/adapter lockを分離する。restricted buildではアダプター追加、`unsafe`の使用許可、Capability発行、機密解除規則の変更をホスト側の検証なしには受理しない。生成コードのコンパイル成功は権限付与を意味しない。

## 5. 並行性・actor・状態遷移

### 5.1 scope/fork/join

`scope { body }`は字句的な子タスク群を所有する。`fork { body }`は原則として仮想スレッドを起動し、`Task<'scope,T>`を返す。ハンドルはscope外へ返せない。`fork`自体は孤児タスクを生成しない。

`Task<T>.join()`は一度だけ消費し、`Result<T, TaskError>`を返す。子が`Result<U,E>`を返す場合、joinの結果は`Result<Result<U,E>,TaskError>`。タスク失敗とドメイン失敗を暗黙に同一化しない。

| 経路 | 必須動作 |
|---|---|
| 正常なscope終了 | 全子タスクを待機。未処理のタスク失敗を`ScopeError`として返す |
| 子のfault / task failure | fail-fastで兄弟へキャンセル要求し、終了を待つ |
| 子が`Err(E)`という値を返した | 自動キャンセルしない。親が処理し、親のErr終了でキャンセルできる |
| 親のErr/早期return/cancel | 未完了の子へキャンセル要求し、join・cleanupしてから退出 |
| deadline超過 | キャンセル要求。強制停止・一定時間内の退出を保証しない |

scope式の正常結果は`Result<T,ScopeError>`。明示joinした失敗は観測済みとして二重報告しない。未観測失敗は生成順で安定して列挙する。scope内の`?`による包含関数の退出でも子タスクの終了待ちを省略しない。

キャンセルは協調的であり、Java/nativeの割り込み非対応呼び出しは停止しないことがある。hard timeoutが必要ならプロセス隔離を使用する。仮想スレッドはCPU並列性を無制限に増やさず、pinningやライブラリ内部ロックも完全には排除しない。

### 5.2 Channelとactor

`Channel<T>`は容量を必須とするbounded channel。満杯ならsendを待機し、キャンセル/closeを型付きエラーで返す。無制限bufferは明示的な別APIとする。`T: Send`を要求し、send成功時に所有権を移譲、失敗時は値を返す。`Share`な不変値の共有はpayloadコピー不要だが、キューの同期コストは存在する。

actorはmailboxから一件ずつ処理し、状態はactor内に閉じる。actor handleはメッセージ送信権限であり、可変状態の共有参照ではない。actorも親scopeに属し、終了・キャンセルを待機する。MVPではメッセージ処理中の再入を認めない。相互待機によるdeadlockを型だけで排除するとは主張しない。

### 5.3 Typestateと外部操作

`resource Reservation<State>`等で状態遷移を表す。`commit`と`release`は`Pending`を消費し、二重使用を拒否する。ただしaffine性だけでは「必ずcommit/releaseする」を保証しないため、未解決の業務resourceは`must_resolve`属性を持ち、全正常/エラー退出経路で解決か復旧記録への移譲を要求する。

```text
commit(Pending) -> Committed | Rejected(Pending) | Unknown(RecoveryToken)
release(Pending) -> Released | Unknown(RecoveryToken)
```

タイムアウトは失敗確定ではない。`RecoveryToken`は操作ID、idempotency key、照会に必要な情報を保持し、信頼された復旧ストアへの永続化を必要とする。クラッシュ時は型検査が実行できないため、外部変更前のdurable intentと冪等APIをアダプター側で実装する。

構造化キャンセルは外部副作用のrollbackではない。不正検知の成功前に残高確保を並列実行するなら、補償と結果不明の復旧が必要。サンプルでは検査を先行させ、決済処理は復旧契約を持つアダプターへ集約する。

## 6. 情報フロー・機密・出典

`Secret<T>`は機密性、`Untrusted<T>`は未検証入力、`Verified<T,S>`は信頼境界を通じて得た出典/検証の証拠を表し、相互に代用しない。

- Secretのmap/連結/シリアライズ結果にもラベルを伝播する。汎用`expose(): T`は提供しない。
- ログ/OTel/JFRにはマスク済みの固定表現か公開値のみを送る。機密を含む例外文・診断・反例・Baggageにも同じ規則を適用する。
- `declassify`にはホスト発行の用途・出力先に束縛されたCapabilityと理由を要求する。操作は監査対象。
- hashや暗号化を一般的な「公開化」とみなさない。出力先に適した変換を信頼されたAPIとして指定する。
- `Untrusted<T>`からの検証は`Result<Validated<T>,ValidationError>`。JSONをparseしただけで業務検証や権限を取得しない。
- 出典の保証を保持できない変換は`Verified`を落とす。AIが任意データに出典ラベルを付けられない。

v0.2の最初の実装は**明示的なデータフロー**を対象とする。機密を条件にした分岐・例外・実行時間などの暗黙フローと側信道は保証外。完全なnoninterferenceやprompt injection耐性を主張しない。program-counterラベル等は後段の検討項目。

## 7. 契約・反例・検証

`requires`は呼び出し前条件、`ensures`は正常な戻り値への後条件。resource invariantは公開操作の出入口で検査する。契約式は純粋で、検査中の副作用・機密出力を禁止する。検証対象の整数は実装のbit幅とoverflow意味論に合わせる。

```rust
pub fn checkedLength(n: Int) -> Result<Long, LengthError> ! {}
    ensures match result { Ok(v) => v >= 0, Err(_) => true }
{
    if n < 0 { Err(LengthError::Negative) } else { Ok(n.toLong()) }
}
```

失敗し得る外部入力は原則`Result`で検証し、契約違反を通常の入力エラーに使わない。実行時の契約違反はfaultとして扱う。契約の評価がfaultになる場合も契約失敗であり、無条件成功にはしない。

検証結果は義務ごとに`proved` / `runtime_checked` / `unproven`を返す。証明器のtimeout/unknownは`unproven`。テスト通過を`proved`に昇格しない。proof-required buildでは未証明を拒否し、それ以外は実行時検査を維持する。公開契約の削除・弱化はAPI変更として差分に表示する。

プロパティテストはseed、生成器version、縮小された反例、ツールチェーンIDを記録する。エージェントに渡すのは機密除去後の再現可能なケース。仕様・契約・hidden testsは実装生成対象と分離し、テストを消して成功扱いにしない。

最初の静的検証は純粋・一次の整数/ADT/境界条件に限定。任意再帰の停止性、並行プログラム全体、外部APIの正しさの自動証明は対象外。

## 8. 観測性・監査・再実行

### 8.1 静的計装

`@trace` / `span`は開始、正常終了、ドメインエラー、キャンセル、faultを区別して記録する。Span終了はcleanupに含め、二重終了を禁止する。`event`からJFRイベントを生成する。計装のための実行時bytecode改変は不要とする。

OTel contextはScopedValueとランタイムadapterで明示的に束縛・継承する。NGJのscope/forkでは自動伝播するが、外部Java executorへはadapterなしに保証しない。Baggageのキー・サイズ・機密性を検査する。O(1)の全体伝播コストは約束しない。

全診断/eventにはbuild ID、source map、symbol IDを関連付けられる。高cardinality属性、payloadサイズ、sampling、exporter backpressureに上限を設ける。通常のtelemetry障害は業務結果を上書きしない。

### 8.2 操作監査

Effect境界では`requested`, `denied`, `succeeded`, `failed`, `unknown`を区別し、operation IDで関連付ける。外部操作の成功はアダプターが確証を得た場合だけ記録する。attemptとcommitを混同しない。

監査必須の操作はsamplingされるOTelとは別のdurable sinkを使う。監査書き込み不能時にfail-closedとするかはホストpolicyで固定し、生成コードが変更できない。トレース欠落を「操作なし」の証拠にしない。

### 8.3 再実行

時刻・乱数・外部I/OをEffect境界で差し替え、入力・応答・seed・依存versionを記録する。replayモードでは実外部副作用を拒否する。fixtureの順序/引数不一致はReplayMismatchとし、黙ってlive呼び出しへfallbackしない。

最初の保証は逐次的な純粋ロジックと記録済みI/O境界の再実行。スレッドスケジュール、native呼び出し、OS状態までの決定的リプレイは含まない。並行実行の順序記録は別段階とする。機密を除去して再現性が失われる場合は、そのケースをreplay不可と明記する。

## 9. Java相互運用と信頼境界

### 9.1 境界署名

Java呼び出しはversion/hash固定のアダプターを経由する。各メソッドにnullability、例外、Effect、Capability、所有権、thread safety、キャンセル、AOT要件を対応付ける。

| Javaの性質 | NGJでの扱い |
|---|---|
| nullableな戻り値 | `Option<T>` |
| 確認済みnon-null戻り値 | `T`。契約違反のnullは境界fault |
| nullability不明 | `Option<T>`またはアダプターの明示検証。無条件nonnullは禁止 |
| 回復可能例外 | `Result<T, AdapterError>`へ明示mapping |
| 割り込み | キャンセルとして伝播。通常の業務失敗へ黙って変換しない |
| VM致命障害 | 成功値や通常Errに偽装しない |
| 可変参照/配列 | resource/借用として扱うか防御的コピー |
| reflection/native/dynamic loading | 信頼された許可と到達可能性情報を要する |

全戻り値の一律Option化はしない。全例外の無損失・ゼロコスト変換も主張しない。未知のEffect/所有権をpureとして推論せず、未検証境界として拒否または隔離する。

### 9.2 Strict AOTとunsafe

Class-File APIによる走査は既知のreflection、Unsafe、native、dynamic loading、未解決呼び出しを発見する補助であり、任意の依存JARの安全性証明ではない。推移的依存とruntime profileも検査対象。

- NGJの通常コードから実行時reflectionは禁止。
- 選択したAOT profileで許可されるJava内部reflectionは、信頼されたadapterとreachability metadata、Native Imageのbuild/smoke testを必要とする。
- `unsafe java_interop`は監査対象の境界表示であり、許可の発行ではない。restricted sourceでは禁止し、allowlistに固定されたadapter module内だけに限定する。
- v0.1の`aot_verified = true`という自己申告は廃止。検証結果はツールチェーン・依存hash・metadata・試験結果に紐付いたbuild artifactで表す。
- `synchronized`だけを根拠にJava 25向けコードを拒否しない。JDK 24のJEP 491による改善を踏まえ、native pinning、ロック競合、割り込み非対応等を別々に評価する。

プロセス全体の隔離が必要ならOS/container境界も使う。安全でないnative依存を型注釈だけで安全に変更することはできない。

## 10. バックエンド・特殊化・配布

### 10.1 パイプライン

```text
Source -> lossless syntax + holes -> name/type/effect/ownership checks
       -> typed IR + contracts + source map -> backend lowering
       -> .class/.jar + ABI manifest + interop/reachability metadata
       -> JVM or separately validated Native Image build
```

Pratt parser等で式を解析し、Java標準`java.lang.classfile` APIで直接class fileを生成する。Javaソースへのtranspileは必須経路にしない。

型・Effect・所有権・公開契約は共通IRで検査し、バックエンド切替で意味論を変えない。ABI manifestには型/Effect/所有権署名とschema versionを含める。

| target/profile | データ表現 | 前提・制限 |
|---|---|---|
| `java25` | final class + 閉じたADT表現 | 基本コンパイラの必須backend。通常の割当あり |
| `valhalla` | 対応VMのvalue class表現 | JDK build/preview/class-file仕様をlock。未対応なら明示エラー |
| `native-image` | java25等の対応出力からAOT | 別build profile。JDK、GraalVM、OS/arch、metadata、JFR等を個別検証 |

Loom/ScopedValue/StructuredTaskScopeの利用可否・preview flagsはprofileに記録し、使用JDKでのAPI差分はruntime adapterに閉じる。Java 25のclass-file生成がpreview APIなしの実行を意味するわけではない。Valhallaのflagsや配置規則を将来の全JDKに固定しない。LeydenとNative Imageも同一機能として扱わない。Project Leyden AOTとnative-imageを統一的に扱うAOTレベル設計については[AOT設計方向](AOT.md)を参照。

### 10.2 特殊化と性能

参照型ジェネリクスの共有と、選択的なプリミティブ/値型特殊化を採用する。小型値型128bit以下は初期heuristic候補であり、ABI・レイアウト保証ではない。特殊化数・コードサイズにbudgetを設け、超過時の共有表現fallbackを診断する。

公開ABIはbackend別manifestで固定し、特殊化された内部名を公開APIとして露出しない。共有表現でボクシングする場合は許容し、allocation reportに表示する。VMによるflattening、scalar replacement、vectorizationは測定すべき最適化であり、型の意味論ではない。

`--emit-layout-report`は判明している配置・特殊化・潜在的割当と未知部分を出力する候補機能。将来の`noalloc`属性は、静的に検証可能な部分集合のみに限定し、未証明の全経路に付けてはならない。

## 11. 実装段階と未決事項

詳細な完了条件は[VALIDATION.md](VALIDATION.md)を参照する。

1. **Phase 1:** fn/data/enum/Result/Option、module、最小型検査、java25出力、Typed Holes、構造化診断、型文脈検索。Effect宣言を保存し、未実装の強制機能を黙って受理しない。
2. **Phase 2:** Effect/Capability、所有権・freeze、Bytes/Arena、Java adapterの信頼境界、restricted build。小さい意味論モデルとnegative testsを先に用意する。
3. **Phase 3:** scope/fork/Channel/actor、Typestate、契約・property tests、明示的情報フロー、OTel/JFR、逐次I/O replay。
4. **Phase 4:** Valhalla、選択的特殊化、Native Image互換性行列、限定的証明器、AI生成の比較実験。

AOTレベル（JIT/Leyden/native-image）の統一的なUXとProject Leyden対応については[AOT設計方向](AOT.md)を参照。Phase 4以降の実装計画として位置づける。

未決事項: 完全なEBNF、効果多相性の一般化範囲、actor supervisionの拡張、非字句借用、任意handler、暗黙情報フロー、並行replay、証明器の具体選定。未決機能を利用するソースは`unsupported_feature`として拒否し、実装済み保証と混在させない。

## 12. v0.1からの変更

- AIによる生成・修正を言語設計の目的に追加し、検査器の外部APIを仕様化。
- `Result`に統一し、union代替・一律Option化・自己申告AOT検証を削除。
- 値型=無割当、全I/O=非blocking、チャネル=無lock等の無条件な性能保証を撤回し、測定対象へ変更。
- freeze、Arena、join、キャンセル、cleanup、外部操作の結果不明を詳細化。
- Effect/Capability、Typestate、情報フロー、契約、再実行、信頼境界を追加。
- サンプルは安全なアダプター境界を使う設計例へ更新。コンパイル済みと誤認させない。
