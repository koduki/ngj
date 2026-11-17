# Design examples

[syntax_v03.ngj](syntax_v03.ngj)をv0.3正規構文の基準例とする。`incomplete`関数には意図的なholeがあり、`ngjc check`では`incomplete`（exit 2）として検査され、release buildでは拒否される。

以下の既存例はv0.2のアーキテクチャ設計例として保存する。`app.*` adapterを省略した例であり、実行可能なテストfixtureは`compiler/src/test/resources`にある。旧例の`module`/`pub`/`data`/`let`/`! {}`はv0.3で受理する別名ではない（関数の戻り値には`return <expr>;`が必須）。変更規則は[SYNTAX_MIGRATION.md](../docs/SYNTAX_MIGRATION.md)を参照する。

| ファイル | 示すもの | 特に必要な試験 |
|---|---|---|
| [zero_copy_protocol.ngj](zero_copy_protocol.ngj) | 負長/末尾不足/overflowを避ける境界検査、入力寿命付きview | OWN-02/03。戻り値よりArenaが先に閉じないこと |
| [payment_service.ngj](payment_service.ngj) | 不正検知先行、機密tokenの許可sink、復旧責務 | STATE-01/02。未知結果の再照会と冪等性 |
| [rest_api.ngj](rest_api.ngj) | HTTP入力検証とJDBC/Secretの信頼境界 | FFI-01/02、IFC-01/02 |
| [cli_tool.ngj](cli_tool.ngj) | bounded並列処理、所有者存続、Task/Scopeエラーの区別 | CONC-01/02、行境界とworker範囲 |
| [agent_holes.ngj](agent_holes.ngj) | context取得用の未実装式 | HOLE-01/02。release buildでは必ず拒否 |

IDは[VALIDATION.md](../docs/VALIDATION.md)を参照。意図的なholeを含むファイルを、通常の成功fixtureに混ぜない。

## アプリ境界の必須契約

サンプルの短さを保つため以下のAPIを省略している。実装時にはstub/adapterを用意して全ファイルを型検査し、下表を満たすこと。未知の署名をpureとして扱うことはできない。

| API/型 | 必須の署名・性質 |
|---|---|
| `PaymentRequest` | `id: PaymentId`, `cardToken: Secret<String>`, 金額等の検証済み不変入力 |
| `checkFraud` | `(&FraudRead, &PaymentRequest) -> Result<FraudDecision, PaymentError> ! {FraudRead}`。決済状態を変更しない |
| `chargeRecoverable` | `(&PaymentGateway, PaymentRequest) -> Result<Receipt, PaymentError> ! {PaymentWrite}`。対象gatewayへの機密送信をホストが許可 |
| `PaymentError` | 少なくともFraudDetected、確定拒否、OutcomeUnknown(operationId)を区別。Secretを含む生の例外文を持たない |
| `Receipt` | 決済成功の確証がある時だけ生成。paymentIdを含む不透明な不変値 |
| `PaymentGateway` | アダプター内部の通信、durable intent、照会/復旧を統合した権限。PaymentWriteの意味にそれらを含め、隠れた無制限Net権限を与えない |
| `UserRead`/`UserWrite` | ホスト発行、対象DB/tenantに束縛されたCapability。JDBC connectionそのものではない |
| `CreateUserReq` | JSON入力型。passwordはSecret。parseで業務検証済みとはしない |
| `validateCreateUser` | `CreateUserReq -> Result<ValidatedCreateUser, ValidationError> ! {}`。検証済み型のconstructorは非公開 |
| `findById` | `(&UserRead, UserId) -> Result<Option<UserRes>, DbError> ! {DbRead}` |
| `createUser` | `(&UserWrite, ValidatedCreateUser) -> Result<UserRes, DbError> ! {DbWrite}`。password処理/DB保存を許可sink内で行う。salt等の乱数使用もこのアダプター契約に含める |
| `UserRes`/`jsonResponse` | 公開データだけをJSON化する純粋API。HTTP送信はhost routerのEffect |
| `partitionLines` | `Bytes<'a>, Int -> List<Bytes<'a>> ! {}`。worker数以下に分割し、改行/UTF-8を分断しない。空入力は空list |
| `countErrors` | `Bytes<'a> -> SummaryReport ! {}`。行の定義とerror判定を固定、末尾改行なしにも対応 |
| `combine` | `(SummaryReport, SummaryReport) -> Result<SummaryReport, Overflow> ! {}` |
| `SummaryReport` | 不変の`totalLines: Long`, `errorCount: Long`。公開値のJsonCodecをderive |

複合アダプターのEffectは、その契約で許可した操作集合を表す。アプリが単に`Net`を`DbRead`に名前変更して権限を隠すことは禁止。内部操作・下位Capability・監査イベントも信頼されたmanifestに記録する。

## 決済アダプターの復旧条件

`chargeRecoverable`は外部変更前にoperation ID/idempotency keyを永続化し、成功/確定拒否/結果不明を保存する。Unknown時は同じ操作の照会を行い、未実行が確認できない限り新しいkeyで重複請求しない。補償自体も失敗・結果不明になり得る。

外部APIがidempotencyや照会を持たない場合、この契約を満たすと宣言してはならない。サンプルの関数名だけでexactly-onceが保証されるわけではない。手動復旧を必要とする状態も記録する。

## CLIの所有権

readOwnedBytesは安全に共有可能なbacking storageを所有するOwnedBytesを返す。viewはread-onlyかつShareであり、scopeが全taskを終了させるまで所有者contentが存続する。thread-confined ArenaのBytesへの差し替えは同じ例を成立させず、検査で拒否すべき。
