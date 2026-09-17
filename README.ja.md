# Device ID Provider
[![openupm](https://img.shields.io/npm/v/com.styly.device-id-provider?label=openupm&registry_uri=https://package.openupm.com)](https://openupm.com/packages/com.styly.device-id-provider/)

[English](README.md) | [日本語](README.ja.md)

Device ID Provider は、[`Styly.Device`](Packages/com.styly.device-id-provider/Runtime/DeviceIdProvider.cs) ランタイムパッケージを使って、現在のデバイスに対する安定した仮名識別子（GUID）を取得する方法を示す Unity サンプルプロジェクトです。起動時に取得した識別子を表示する小さなシーンと、Android、Windows、macOS 向けの参照実装を含みます。

デバイス ID の概念、保存先、運用方法については、[デバイス ID とは？](docs/device-id.md)を参照してください。

## 必要環境

- Unity 6000.0 以降。
- プロバイダーの実装が用意されている次のプラットフォームを対象とします。
  - Android API レベル 29 以降。
  - Windows の Player と Editor。
  - macOS の Player と Editor。

## はじめに

1. Unity 6000.0 以降でプロジェクトを開きます。
2. サンプルシーンを読み込み、Play を押します。
3. `GetDeviceID` MonoBehaviour が起動時に GUID を取得し、UI のテキスト要素に表示します。

```csharp
using Styly.Device;
...
void Start()
{
    text.text = DeviceIdProvider.GetDeviceID();
}
```

## インストール

[OpenUPM CLI](https://github.com/openupm/openupm-cli) をインストールし、Unity プロジェクトのディレクトリで次のコマンドを実行します。

```bash
npm install -g openupm-cli
openupm add com.styly.device-id-provider
```

## GUID 生成の仕組み

ランタイムパッケージは、実行時に `Application.platform` に応じてプラットフォーム固有の実装を選択し、静的 API `DeviceIdProvider.GetDeviceID()` を通じて提供します。未対応プラットフォームでは、制約を明示するために `PlatformNotSupportedException` をスローします。

### Android（API 29 以降）

- GUID を共有 MediaStore 内の 1 ピクセルの PNG として保存するため、Android 10（API 29）以降が必要です。ユーザーが共有画像を削除しない限り、アプリを再インストールしても識別子を維持できます。
- 画像は `Pictures/Device-ID-Provider/` に、GUID に拡張子 `.png` を付けたファイル名で作成されます。プロバイダーは常に条件に一致する最も古いエントリを返し、実行のたびに識別子が変わらないようにします。
- Android の基準となる MediaStore 実装は Java の Android ライブラリです。Unity の C# プロバイダーは、ネイティブ Android アプリでも利用できる同じ AAR を呼び出す薄い JNI ラッパーです。
- 候補の選択には `date_added ASC, _id ASC` を使い、有効な画像が複数あっても選択結果が一意に決まるようにしています。MediaStore にはプロセス間でアトミックに比較・更新する操作がないため、初回の呼び出しが同時に行われると、双方が画像を挿入する場合があります。最終検索とそれ以降のすべての検索では、同じ公開済みの画像が選ばれるように収束します。
- 実行時権限：
  - API レベル 32 以下：`READ_EXTERNAL_STORAGE` を要求します。
  - API レベル 33 以上：`READ_MEDIA_IMAGES` を要求します。
  - API レベル 34 以上：写真への部分的なアクセスと全体へのアクセスを区別するため、`READ_MEDIA_VISUAL_USER_SELECTED` も要求します。部分的なアクセスではアプリ間で共通の正規 ID を確定できないため、受け付けません。

  Unity ラッパーは権限が付与されるまでタイムアウト付きで待機し、ユーザーが拒否した場合は `UnauthorizedAccessException` をスローします。ネイティブライブラリ自体は Activity の起動や権限要求 UI の表示を行わないため、画面を持たないサービスからも呼び出せます。
- ホストアプリがすでに全ファイルへのアクセス権を持っている場合は、`MANAGE_EXTERNAL_STORAGE` も受け付けます。画像全体へのアクセス権がない状態では、検索結果が空でも別のアプリが ID を作成済みでないことを確認できないため、ライブラリは閲覧範囲が制限された MediaStore からの ID 新規生成を拒否します。

### ネイティブ Android ライブラリ

Android 実装の正本は `android/` 配下の Gradle プロジェクトです。次の API を提供します。

```java
DeviceIdResult result = DeviceIdProvider.getOrCreate(applicationContext);
```

`DeviceIdResult` は、`SUCCESS`、`NOT_FOUND`、`ACCESS_DENIED`、`UNSUPPORTED_API`、`IO_ERROR` のいずれかのステータスに加えて、選択された ID と候補数を返します。権限要求の UI と再試行方針は、ホストアプリ側で管理します。

#### ネイティブ Android ホスト向けの任意の非同期 API

Unity の C# API とその同期動作は変わりません。デバイス起動時に動作するネイティブ Android ホストでは、代わりに次の API を使用できます。

```java
CompletableFuture<DeviceIdResult> request = DeviceIdProvider.getOrCreateAsync(
        applicationContext, 30_000L, 250L); // タイムアウトと再試行間隔の例（ミリ秒）。
request.thenAcceptAsync(result -> {
    if (result.getStatus() == DeviceIdStatus.SUCCESS) {
        useDeviceId(result.getDeviceId());
    } else {
        handleProviderFailure(result);
    }
}, applicationContext.getMainExecutor());
// 例外による完了（TimeoutException または予期しない初期設定の失敗）も処理してください。
// この要求を所有する処理が終了するとき：
// request.cancel(false);
```

- どちらの時間指定も正の値である必要があります。指定した再試行間隔で、画像全体へのアクセス権、マウント状態、プライマリボリュームに対する読み取り専用クエリを確認します。マウント監視のオブザーバーは登録しません。
- ストレージが未マウントの場合や、固定の準備状態確認処理が `IllegalArgumentException` を返した場合は、期限まで再試行します。ファイルシステムがマウントされているだけでは、MediaStore が利用可能とは判断しません。
- 準備状態の確認に成功すると、既存の `getOrCreate` を一度呼び出し、`IO_ERROR` を含めてその結果を返します。その後にボリュームが切断されても内部では再試行しません。ホスト側で新しい要求を開始できます。
- 権限拒否、未対応の API、その他の準備状態確認の失敗は、その時点で処理を終了します。ライブラリが権限要求 UI を開くことはありません。
- 独立したタイマーにより、検索処理がブロックしていても Future は `TimeoutException` で完了します。その原因には、直近の準備状態確認の例外または未マウント状態が保持されます。
- キャンセルすると、以降の試行を停止し、再試行の待機を解除します。タイムアウトもキャンセルも、開始済みの検索の中断、作成済み ID の取り消し、書き込みが行われなかったことの保証はしません。遅れて返った結果は破棄され、ブロック中のワーカーは実行中の処理が戻った時点で終了します。
- 完了ハンドラーには明示的な Executor を指定してください。メインスレッドを `get()` / `join()` でブロックしたり、返された Future を手動で完了させたりしないでください。
- この API が待つのはボリュームへのアクセスが可能になるまでであり、すべてのバックグラウンドメディアスキャンの完了ではありません。既存の候補選択と ID 新規生成の仕様は変わりません。ID を取得できたと判断できるのは `SUCCESS` の場合だけです。

既存 ID を確認するインストルメンテーションテストには、画像全体へのアクセス権と既存のマーカー画像が必要です。条件を満たさない場合はスキップされます。このテストは権限を付与した状態で、アクセス拒否のテストは権限を取り消した状態で実行し、インストルメンテーションの生のステータス出力でスキップの有無を確認してください。テスト APK のターゲット API は、利用側アプリとは独立して 34 に設定されています。起動サイクルの検証では、実機の起動中にこのネイティブ API を呼び出す必要があります。起動済みの状態でインストルメンテーションテストが成功するだけでは不十分です。

JDK 17 と Android SDK を使って、ライブラリをビルド・テストします。

```bash
./android/gradlew -p android testReleaseUnitTest
./android/gradlew -p android :device-id-provider:syncUnityAar
```

2 つ目のコマンドは、`Packages/com.styly.device-id-provider/Plugins/Android/styly-device-id-provider.aar` にコミットされている AAR を再ビルドします。同じリリースコンポーネントは、`:device-id-provider:publishReleasePublicationToMavenLocal` でローカル Maven リポジトリにインストールできます。リモートへ公開する場合は、事前に Gradle の publishing ブロックで公開先リポジトリを設定してください。

### Windows と macOS

- スタンドアロン向けプロバイダーは、ユーザーのアプリケーションデータディレクトリ内のテキストファイルに GUID を保存します。
    - Windows : `%LOCALAPPDATA%/Styly/Device-ID-Provider/device.id`
    - macOS : `~/Library/Application Support/Styly/Device-ID-Provider/device.id`
- Unity Editor では、`Application.dataPath` の安定したハッシュ値から生成したサブディレクトリに GUID を保存します。Editor 全体で 1 つのファイルを共有するのではなく、プロジェクトディレクトリ、ParrelSync のクローン、Multiplayer Play Mode の仮想プレイヤーごとにデバイス ID を分離します。
- これらの特殊フォルダーが利用できない場合（制限された環境など）は、`Application.persistentDataPath` にフォールバックします。
- 既存ファイルの内容を検証し、ファイルが存在しない場合や破損している場合は GUID を再生成します。

### 未対応プラットフォーム

上記以外のプラットフォーム（iOS、WebGL など）では、現在 `GetDeviceID()` を呼び出すと `PlatformNotSupportedException` がスローされます。

## パッケージ構成

```
Packages/com.styly.device-id-provider/
├── Plugins/Android/
│   └── styly-device-id-provider.aar       # Unity が使用するネイティブ Android 実装
├── Runtime/
│   ├── DeviceIdProvider.cs                # 実行時に実装を選択するエントリーポイント
│   ├── Providers/
│   │   ├── AndroidDeviceIdProvider.cs     # Android AAR 用の薄い Unity JNI ラッパー
│   │   ├── StandaloneDeviceIdProvider.cs  # Windows/macOS 向けのファイルによる永続化
│   │   └── UnsupportedDeviceIdProvider.cs # その他のプラットフォームで例外をスロー
│   └── Internal/
│       └── AndroidBridge.cs               # 最小限の Unity <-> Android JNI ヘルパー
└── package.json                           # パッケージのメタデータ
```

ネイティブ Android のソース、テスト、AAR のビルド設定、Maven の公開設定は、`android/device-id-provider/` にあります。
