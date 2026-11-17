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

# ソースファイルのチェック
docker run --rm -v $(pwd)/examples:/src ngj:latest check /src/pricing.ngj

# JSON形式で出力
docker run --rm -v $(pwd)/examples:/src ngj:latest check --format=json /src/pricing.ngj

# ビルド（クラスファイル生成）
docker run --rm -v $(pwd)/examples:/src -v $(pwd)/out:/out ngj:latest build -o /out /src/pricing.ngj
```

## イメージ構成

| ターゲット | ベースイメージ | 用途 | サイズ目安 |
|-----------|---------------|------|-----------|
| `builder` | JDK 25 | Gradle ビルド用 | - |
| `runtime` | JDK 28 EA | 本番実行用（Valhalla） | ~200MB |
| `development` | JDK 28 EA | 開発・テスト用 | ~500MB |

> **Note**: NGJ は JDK 28 EA with JEP 401 (Value Objects) をターゲットとしています。
> Gradle ビルドには JDK 25 を、ランタイムには JDK 28 を使用します。

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
  eclipse-temurin:25-jdk \
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

- **ビルド用 Java バージョン**: 25 (Gradle 実行用)
- **ランタイム Java バージョン**: 28 EA (JEP 401 Value Objects)
- **バイトコード生成**: Class-File API（標準ライブラリ、外部依存なし）
- **ビルドツール**: Gradle 9.9.1
