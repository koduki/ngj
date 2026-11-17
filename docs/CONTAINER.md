# NGJ コンテナガイド

NGJ コンパイラをコンテナ環境でビルド・実行するためのガイドです。

## 前提条件

- Docker 20.10 以上
- Docker Compose v2（オプション）

## クイックスタート

### コンパイライメージのビルド

```bash
# runtime イメージ（推奨、最小サイズ）
docker build --target runtime -t ngj:latest .

# development イメージ（JDK付き、テスト実行可能）
docker build --target development -t ngj:dev .
```

### コンパイラの実行

```bash
# ヘルプ表示
docker run --rm ngj:latest --help

# バージョン確認
docker run --rm ngj:latest --version

# ソースファイルのチェック（syntax_v03.ngjは意図的なholeを含むv0.3正規構文例のためcheckはincomplete / exit 2）
docker run --rm -v $(pwd)/examples:/src ngj:latest check /src/syntax_v03.ngj

# 完全なv0.3ソースのチェック（exit 0 / valid）
docker run --rm -v $(pwd)/compiler/src/test/resources/fixtures:/fixtures ngj:latest check /fixtures/examples/pricing_complete.ngj

# JSON形式で出力
docker run --rm -v $(pwd)/compiler/src/test/resources/fixtures:/fixtures ngj:latest check --format=json /fixtures/examples/pricing_complete.ngj

# ビルド（クラスファイル生成、build/classesに出力；holeのない完全なソースが必要）
docker run --rm -v $(pwd)/compiler/src/test/resources/fixtures:/fixtures -v $(pwd)/build:/app/build ngj:latest build /fixtures/examples/pricing_complete.ngj

# Note: examples/syntax_v03.ngjはholeを含むためngjc buildではexit 2（NGJ-BUILD-001）で拒否される。
# examples/の他の5ファイル（payment_service.ngj等）はv0.2設計スケッチでありcheckでinvalid（exit 1）となる。
# 詳細はexamples/README.mdを参照。
```

## イメージ構成

| ターゲット | ベースイメージ | 用途 | サイズ目安 |
|-----------|---------------|------|-----------|
| `builder` | eclipse-temurin:25-jdk-jammy | 内部ビルド用（Gradleコンパイル） | - |
| `runtime` | JDK 28 EA Build 24 + debian:bookworm-slim | 本番実行用（`--enable-preview`でJEP 401対応） | ~200MB |
| `development` | eclipse-temurin:25-jdk-jammy | 開発・テスト用 | ~500MB |

## 開発環境

### 開発コンテナでテスト実行

```bash
# development イメージをビルド
docker build --target development -t ngj:dev .

# テスト実行
docker run --rm ngj:dev ./gradlew test --no-daemon

# インタラクティブシェル
docker run --rm -it ngj:dev bash
```

### ボリュームマウントでの開発

ホストのソースコードを変更しながらコンテナでビルド：

```bash
docker run --rm \
  -v $(pwd):/workspace \
  -w /workspace \
  eclipse-temurin:25-jdk-jammy \
  ./gradlew build --no-daemon
```

## CI/CD での利用

### GitHub Actions での利用例

```yaml
jobs:
  container-build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Build container image
        run: docker build --target runtime -t ngj:${{ github.sha }} .
      
      - name: Test container
        run: docker run --rm ngj:${{ github.sha }} --version
```

### マルチプラットフォームビルド

```bash
# buildx を使用して linux/amd64 と linux/arm64 両方をビルド
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --target runtime \
  -t ngj:latest \
  --push \
  .
```

## トラブルシューティング

### ビルドが遅い場合

Gradle の依存関係キャッシュを活用するため、`gradle/` と Gradle wrapper を先にコピーしています。
初回ビルド後は依存関係レイヤーがキャッシュされ、ソース変更時の再ビルドが高速になります。

### メモリ不足エラー

JVM のヒープサイズを調整：

```bash
docker run --rm -e JAVA_OPTS="-Xmx512m" ngj:latest check /src/file.ngj
```

### パーミッションエラー

runtime イメージは非 root ユーザー（`ngj`）で実行されます。
ボリュームマウント時にパーミッション問題が発生する場合：

```bash
# ユーザーID を指定して実行
docker run --rm -u $(id -u):$(id -g) -v $(pwd):/src ngj:latest check /src/file.ngj
```

## 技術仕様

- **Java バージョン**: 25+ (Eclipse Temurin) for `valhalla-compat`; JDK 28 EA + `--enable-preview` for full Valhalla value classes
- **バイトコード生成**: Class-File API（標準ライブラリ、外部依存なし）
- **ビルドツール**: Gradle 9.9.1
