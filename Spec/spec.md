# Device ID Provider 仕様インデックス

このプロジェクトの仕様は API レベル別に 2 ファイルへ分割されています。詳細は各ファイルを参照してください。

- Base（API ≤ 32, MediaStore）: `Spec/spec_base.md`
- Plus（API ≥ 33, SAF）: `Spec/spec_plus.md`

概要
- 両仕様とも「JSON ファイルに保存した GUID を複数アプリで共有する」ことを目的とします。
- 実装は Android ライブラリ（Java, AAR）＋ Unity C# ラッパの構成。
- Base は MediaStore（Downloads）を利用して確実動作を優先。
- Plus は SAF を利用し、初回のみユーザー操作（フォルダ選択）で永続権限を確保します。

参照
- 実装スケルトン: `Android/device-id-provider/`
- Unity ラッパ API: `Packages/com.styly.device-id-provider/Runtime/DeviceIdProviderUnity.cs`

## 5. 保存場所・フォーマット
- MediaStore コレクション: `MediaStore.Downloads`
- 相対パス（`RELATIVE_PATH`）: `"Download/Device-ID-Provider/"`
- ファイル名（`DISPLAY_NAME`）: `"device-id-provider.json"`
- MIME: `"application/json"`
- JSON キー名: `"device-id"`（固定）

## 6. 権限・アクティビティ構成
- API ≤ 32:
  - 読み取り: `READ_EXTERNAL_STORAGE` を初回のみランタイムリクエスト。
  - 書き込み: MediaStore 経由で原則不要（挿入時に付与される）。
- API ≥ 33:
  - `READ_MEDIA_*` では JSON は対象外。
  - SAF を使用（`ACTION_OPEN_DOCUMENT_TREE` でフォルダ選択、または `ACTION_CREATE_DOCUMENT` でファイル作成）。
  - 取得した URI に対して `takePersistableUriPermission` を呼び出し、永続化。

推奨 Manifest 例（ライブラリ側）
```xml
<manifest>
  <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"/>

  <application>
    <!-- SAF ダイアログ専用（透過・内部用） -->
    <activity
        android:name="com.styly.deviceidprovider.ui.IdProviderUiActivity"
        android:exported="false"
        android:theme="@style/Theme.Transparent.NoActionBar"/>
  </application>
</manifest>
```

## 7. 実装概要（アーキテクチャ）
- 構成
  - Java ライブラリ（AAR）: `com.styly.deviceidprovider` パッケージ。
    - `DeviceIdProvider`（公開ファサード）
    - `MediaStoreHelper`（API ≤ 32）
    - `SafHelper`（API ≥ 33）
    - `PermissionHelper`（権限要求）
    - `IdProviderUiActivity`（SAF/権限ダイアログ起動・結果受領の透明 Activity）
  - Unity C# ラッパ: 非同期 `GetDeviceID(Action<string> onSuccess, Action<string,string> onError)` を提供。
- フロー
  - API 判定で分岐:
    - API ≥ 33: 永続 URI を確認 → 無ければ `IdProviderUiActivity` 経由で SAF 表示 → フォルダ/ファイル確保 → 読取/作成。
    - API ≤ 32: MediaStore 検索 → 無ければ作成（`IS_PENDING` 利用）→ 読取。
  - キャッシュ: 取得済み ID は `SharedPreferences` とメモリにキャッシュ（I/O 削減、整合性はファイル優先）。
  - 破損時: ログ出力後に新 GUID を再生成し上書き保存。

## 8. 実装詳細（Java ライブラリ）
### 公開 API（Java）
```java
package com.styly.deviceidprovider;

public final class DeviceIdProvider {
  public interface Callback {
    void onSuccess(String deviceId);
    void onError(String code, String message);
  }

  // 非同期。必要に応じて権限/SAF を表示し、完了後にコールバック。
  public static void getDeviceId(@NonNull Activity activity, @NonNull Callback cb) { /* ... */ }

  // 事前に SAF の永続 URI を設定済みであれば同期取得も可能（キャッシュ優先、未整備なら null）。
  @Nullable public static String getDeviceIdIfReady(@NonNull Context context) { /* ... */ return null; }
}
```

### MediaStore（API ≤ 32）
- `RELATIVE_PATH = "Download/Device-ID-Provider/"`、`DISPLAY_NAME = "device-id-provider.json"` でクエリ。
- 既存があれば UTF-8 読み込み → JSON 解析 → `device-id`。破損時は再生成して上書き。
- 不在なら新規 GUID 生成 → `IS_PENDING=1` で挿入（API 29–30）→ JSON 書込み → `IS_PENDING=0` に更新。
- 例外は `code`（例: `E_PERMISSION`, `E_IO`, `E_JSON`）付きでコールバック。

擬似コード（抜粋）
```java
Uri existing = MediaStoreHelper.findSingle(resolver, FOLDER, FILE);
if (existing != null) {
  String id = tryReadGuid(resolver, existing); // may return null on corruption
  if (id != null) return success(id);
  // corruption: fall-through to regenerate
}
String newId = newGuid();
Uri uri = MediaStoreHelper.insertJson(resolver, FOLDER, FILE, /*pending=*/true);
writeJson(resolver, uri, jsonFor(newId));
MediaStoreHelper.completePendingIfNeeded(resolver, uri);
return success(newId);
```

### SAF（API ≥ 33）
- 初回に `ACTION_OPEN_DOCUMENT_TREE`（推奨）でフォルダを選択、`takePersistableUriPermission` で永続化。
- 選択フォルダ直下に `device-id-provider.json` を `DocumentFile` で探索/作成。
- 読み込み失敗（破損）時はログ出力後に新規 GUID を再生成して上書き。
- ライフサイクルは透明 `IdProviderUiActivity` が担当（結果を静的レジストリ経由で `Callback` に返却）。

擬似コード（抜粋）
```java
Uri tree = SafHelper.ensureTreeUri(activity); // may show UI once
DocumentFile dir = DocumentFile.fromTreeUri(activity, tree);
DocumentFile file = SafHelper.findOrCreate(dir, FILE, "application/json");
String id = tryReadGuid(activity.getContentResolver(), file.getUri());
if (id == null) {
  id = newGuid();
  writeJson(activity.getContentResolver(), file.getUri(), jsonFor(id));
}
return success(id);
```

### 共有（API ≥ 33）
- 各アプリが同一フォルダを選択すれば同一ファイルを共有可能。
- 選択ミスによる分岐を避けるため、UI 文言で `Download/Device-ID-Provider/` を推奨選択として案内。

### SAF（API ≥ 33）
- ユーティリティ（例: `SafBridge.cs`）
  - `ACTION_OPEN_DOCUMENT_TREE` でフォルダ選択（推奨）し、選択フォルダ直下に `device-id-provider.json` を `DocumentFile` で作成/取得。
  - 代替: `ACTION_CREATE_DOCUMENT` でファイルを直接作成/選択。
  - `takePersistableUriPermission` によりツリー URI またはドキュメント URI の読み書き権限を永続化。
  - `ContentResolver.openInputStream` / `openOutputStream` で I/O。
- 既存ファイル探索
  - フォルダ選択時は `DocumentFile.findFile("device-id-provider.json")` で探索。
  - 不在なら新規 GUID を生成し、ファイルを作成して JSON を書き込み。
- 永続 URI の保存
  - 取得した URI を `Application.persistentDataPath` などに保存（文字列）。
  - 次回以降はダイアログ不要で同 URI を再利用。
- 破損時
  - 読み込み/JSON 解析失敗時はログ出力の上、新規 GUID を再生成し上書き保存。

## 9. 動作確認チェックリスト
- 既存 JSON あり → 同じ ID を返す。
- JSON なし → 新規生成・保存・返却。
- 2 本のアプリ（A/B）で片方が作成 → もう片方が読み出せる。
  - API ≤ 32: MediaStore 経由で横断参照を確認。
  - API ≥ 33: 両アプリで同一フォルダ/ファイルを SAF で選択 → 同一 ID が返ることを確認。
- 初回のみのダイアログ（権限/SAF）表示と再試行の挙動確認。
- 同時起動（A/B 同時に初回 `GetDeviceID`）で二重作成が起きない／起きても参照は一意。
- 文字化けなし（UTF-8）。
- JSON 破損時にエラーログ出力の上で再生成・上書きされること。

## 10. 既知の制約と注意
- API 33+ では SAF の永続権限はアプリ単位であり、各アプリが個別に同一フォルダ/ファイルを選択する必要がある（ユーザー操作が初回に必要）。
- API 33+ でユーザーが別フォルダ/別ファイルを選択した場合、ID が分岐しうるため UI/説明で誘導する。
- ベンダー実装（Pico/Quest）により `RELATIVE_PATH` や SAF の挙動が異なる場合がある。ロギングを手厚くする。

## 11. 参考コード（最小骨子・擬似）
実際の JNI 呼び出し・ストリーム処理はプロジェクトのユーティリティに合わせて実装する。

```csharp
// Unity C# ラッパ（概要）
public static class DeviceIdProviderUnity
{
    public static void GetDeviceID(Action<string> onSuccess, Action<string,string> onError)
    {
        using var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
        using var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
        using var cls = new AndroidJavaClass("com.styly.deviceidprovider.DeviceIdProvider");

        var callback = new DeviceIdCallbackProxy(onSuccess, onError); // AndroidJavaProxy 実装で Java の Callback を受ける
        cls.CallStatic("getDeviceId", activity, callback);
    }
}
```

## 12. 仕様確定事項
- 対応方針: API 33+ は SAF を導入、API 32 以下は MediaStore で確実動作を保証。
- 初回ダイアログ: 許容（API ≤ 32 の権限付与／API ≥ 33 の SAF 選択）。
- 破損時挙動: エラーログ出力の上で再生成（新 GUID 上書き）。
- 共有要件: API ≥ 33 は各アプリで同一フォルダ/ファイルの SAF 許可取得が必要。
- 名称固定: フォルダ `Device-ID-Provider`、ファイル `device-id-provider.json`、キー名 `"device-id"`。

## 13. 納品物
- Android ライブラリ（AAR）: `com.styly.deviceidprovider`
  - Java ソース: `DeviceIdProvider.java`, `MediaStoreHelper.java`, `SafHelper.java`, `PermissionHelper.java`, `IdProviderUiActivity.java`
  - `AndroidManifest.xml`（上記 Activity, 権限, テーマ）
  - `build.gradle`（minSdk 29, targetSdk 最新推奨）
- Unity C# ラッパ: `DeviceIdProviderUnity.cs`（`GetDeviceID` 非同期 API）
- サンプル: `Demo.scene`（ボタン・テキストで動作デモ）
- README.md（導入手順、AAR 配置 `Assets/Plugins/Android/`、必要設定、既知の制約）
- テスト観点チェックリスト（本書「9. 動作確認チェックリスト」）
