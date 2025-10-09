# Unity/Android Device ID Provider 仕様（MediaStore / SAF / C#のみ）

## 1. 目的
- Unity C# のみで Android 端末固有の GUID を生成・保持・共有する仕組みを実装する。
- API 33+ もサポートしつつ、API 32 以下で確実に動作することを必須要件とする。
- GUID は JSON を単一ソースとして共有（複数アプリ間で同一値を参照）。
  - API ≤ 32: MediaStore の Downloads コレクション配下に配置。
  - API ≥ 33: SAF（Storage Access Framework）でユーザーが選択したフォルダ/ファイルを用いる。
- 公開 API は `GetDeviceID()` のみ。初回呼び出し時に JSON が無ければ生成して保存、以後は読み出しを返す。

## 2. 前提・範囲
- 保存場所: `Download/Device-ID-Provider/device-id-provider.json`（MediaStore `Downloads` コレクションの `RELATIVE_PATH`）。
- JSON 形式:
  ```json
  { "device-id": "<guid-lowercase-hyphenated>" }
  ```
- GUID 生成: `System.Guid.NewGuid().ToString("D").ToLowerInvariant()`。ファイルが削除されるまで不変。
- 対象デバイス: Pico / Meta Quest 系（Android ベース）。
- 実装言語: Unity C# のみ（ネイティブ AAR なし）。Android API は `AndroidJavaObject` / `AndroidJavaClass` で呼び出す。
- 備考: `Download` はスコープドストレージ配下の共有領域。MediaStore を用いて作成・検索・入出力を行う。

## 3. 動作要件（受け入れ基準）
- `GetDeviceID()` は常に同じ文字列（当該 JSON の内容）を返す。
- JSON が存在しない場合、新規 GUID を生成し、保存し、その値を返す。
- 複数アプリで同一端末・同一 JSON を参照できる。
  - API ≤ 32: MediaStore 参照で横断可。
  - API ≥ 33: 各アプリが一度 SAF で同一フォルダ/ファイルをユーザー選択して永続許可を取得すれば横断可。
- 初回のみのダイアログは許容（ランタイム権限/SAF 選択）。
- 競合を避け、二重作成を最小化する（後述ロジック）。二重作成が起きた場合でも、以後は先に存在するファイルを参照する。
- JSON 破損時はエラーログを出しつつ再生成する（新しい GUID を採番して上書き）。
- 例外（権限未許可、ストレージ不可、I/O エラー等）時は明確なログを出し、`GetDeviceID()` は `null` もしくは例外で呼び出し元に伝播（プロジェクト方針に合わせ選択）。

## 4. Android 設計ポイント（API レベル別）
- 推奨最小 API: 29（Android 10）
- 実運用ターゲット: API 29–32 は MediaStore で確実動作。
- API 33+（Android 13+）: JSON は `READ_MEDIA_*` の対象外のため、横断参照には SAF を用いる。
  - 設計方針: 初回のみ SAF ダイアログ（許容済）でユーザーがフォルダ（推奨: `Download/Device-ID-Provider/`）またはファイルを選択 → `takePersistableUriPermission` で永続化 → 以後は無人で I/O。
  - 既存運用との整合: API 32 以下で生成済みの JSON がある場合、ユーザーに同一フォルダを選んでもらうことで同一ファイルを継続利用可能。

## 5. 保存場所・フォーマット
- MediaStore コレクション: `MediaStore.Downloads`
- 相対パス（`RELATIVE_PATH`）: `"Download/Device-ID-Provider/"`
- ファイル名（`DISPLAY_NAME`）: `"device-id-provider.json"`
- MIME: `"application/json"`
- JSON キー名: `"device-id"`（固定）

## 6. 権限ポリシー
- API ≤ 32:
  - 読み取り: `READ_EXTERNAL_STORAGE` を初回のみランタイムリクエスト。
  - 書き込み: MediaStore 経由で原則不要（挿入時に付与される）。
- API ≥ 33:
  - `READ_MEDIA_*` では JSON は対象外。
  - SAF を使用（`ACTION_OPEN_DOCUMENT_TREE` でフォルダ選択、または `ACTION_CREATE_DOCUMENT` でファイル作成）。
  - 取得した URI に対して `takePersistableUriPermission` を呼び出し、永続化。

## 7. 実装概要
- 公開 API: `GetDeviceID()` のみ。
- API 判定で分岐:
  - API ≥ 33: SAF の永続 URI を確保（なければダイアログ表示）→ 既存 JSON を探索 → 無ければ作成 → 読み出し/返却。
  - API ≤ 32: MediaStore 検索 → 無ければ作成 → 読み出し/返却。
- 競合対策: 先行ファイルを採用。作成中は `IS_PENDING`（API 29–30）を利用。
- 破損時: エラーログを記録し、新規 GUID を採番して再生成/上書き。
- エラーハンドリング: ログを明確化し、返却方針（null/例外）は統一。

## 8. 実装詳細（Android / C# ブリッジ）
### MediaStore（API ≤ 32）
- ユーティリティ（例: `AndroidBridge.cs`）
  - SDK, Activity, ContentResolver の取得。
  - MediaStore `Downloads` の `contentUri` 取得。
  - `Query`/`Insert`/`Update`/`openInputStream`/`openOutputStream` ラッパ。
  - JSON シリアライズ（`JsonUtility` もしくは `System.Text.Json` 相当）。
- DTO
  ```csharp
  class DeviceIdDto { public string device_id; }
  ```
  （JSON は `device-id` キー。シリアライザでカスタム名に対応）
- 検索ロジック（`FindExisting()`）
  - `RELATIVE_PATH = "Download/Device-ID-Provider/"` かつ `DISPLAY_NAME = "device-id-provider.json"` を条件にクエリ。
  - 先頭 1 件の `contentUri` を返す（複数あっても 1 件採用）。
- 読取ロジック（`TryReadGuid(Uri uri, out string guid)`）
  - `openInputStream` → UTF-8 読み込み → JSON 解析 → `device-id` を抽出。
- 作成ロジック（`CreateAndWrite()`）
  - GUID 生成 → `insert()`（API 29–30 は `IS_PENDING=1` 付与）→ JSON 書き込み → 必要に応じ `IS_PENDING=0` 更新 → URI 返却。
- 公開 API（`GetDeviceID()`）
  - パーミッション確認（必要な場合のみ要求）→ `FindExisting()` → あれば `TryReadGuid()`、無ければ `CreateAndWrite()`。
  - 失敗時のログ・例外取り扱いを統一。
- スレッド安全性
  - アプリ内多重呼び出しに対して `lock` でガード。
- フォールバック（API < 29）
  - `Download` 直下に `Device-ID-Provider` フォルダを `File` で作成し、同名 JSON を I/O。
- デモ
  - `Demo.scene` にボタン（`GetDeviceID`）と結果表示 Text。

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
public static class DeviceIdProvider
{
    const string Folder = "Download/Device-ID-Provider/";
    const string FileName = "device-id-provider.json";

    public static string GetDeviceID()
    {
        if (Application.platform != RuntimePlatform.Android)
            return null; // 要件外

        if (AndroidBridge.SdkInt >= 33)
        {
            // SAF フロー（初回のみダイアログ許容）
            var uri = SafBridge.EnsureDocumentUri(Folder, FileName); // ツリー URI の永続化＋ファイル取得/作成
            if (SafBridge.TryReadGuid(uri, out var guid))
                return guid;

            // 破損時は再生成
            var newGuid = System.Guid.NewGuid().ToString("D").ToLowerInvariant();
            SafBridge.WriteJson(uri, $"{{\"device-id\":\"{newGuid}\"}}\n");
            return newGuid;
        }
        else
        {
            // MediaStore フロー（API ≤ 32）
            using var activity = AndroidBridge.GetActivity();
            using var resolver = AndroidBridge.GetContentResolver(activity);
            using var downloads = AndroidBridge.GetDownloadsUri();

            AndroidBridge.EnsurePermissions(); // READ_EXTERNAL_STORAGE など（初回のみ）

            var existing = AndroidBridge.QuerySingle(resolver, downloads, FileName, Folder);
            if (existing != null && AndroidBridge.TryReadGuid(resolver, existing, out var guid))
                return guid;

            var newGuid = System.Guid.NewGuid().ToString("D").ToLowerInvariant();
            var newUri = AndroidBridge.InsertJsonFile(resolver, downloads, FileName, Folder, pending: true);
            AndroidBridge.WriteJson(resolver, newUri, $"{{\"device-id\":\"{newGuid}\"}}\n");
            AndroidBridge.CompletePendingIfNeeded(resolver, newUri);
            return newGuid;
        }
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
- `DeviceIdProvider.cs`（公開 API 実装）
- `AndroidBridge.cs`（JNI ユーティリティ）
- `Demo.scene`（ボタン・テキストで動作デモ）
- `README.md`（ビルド設定、Manifest 権限、既知の制約）
- テスト観点チェックリスト（本書「9. 動作確認チェックリスト」）
