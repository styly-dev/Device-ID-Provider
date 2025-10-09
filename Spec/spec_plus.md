# Unity/Android Device ID Provider 仕様（Plus: API ≥ 33, SAF, Java AAR + Unity Wrapper）

本仕様は Base 仕様（spec_base.md）を拡張し、Android 13+（API 33 以上）向けの SAF（Storage Access Framework）対応を定義する。

## 1. 目的（Plus）
- API 33+ において、非メディア（JSON）を他アプリから共有参照するために SAF を用いる設計を提供する。
- 初回のみのユーザー操作（フォルダ/ファイル選択）を許容し、`takePersistableUriPermission` で永続化する。

## 2. 受け入れ基準（API ≥ 33）
- `GetDeviceID()` は同一端末で常に同じ ID を返す（共有 JSON の値）。
- JSON が無い場合、新規 GUID を生成・保存し、その値を返す。
- 複数アプリで同一端末・同一 JSON を参照できる。
  - 各アプリが初回に同一フォルダ/ファイルを SAF で選択し、永続許可を取得することが前提。
- 初回のみ SAF ダイアログは許容。
- JSON 破損時はエラーログ出力の上で新 GUID を再生成・上書き保存。

## 3. 権限・アクティビティ（Plus）
- `READ_MEDIA_*` は JSON 対象外のため、SAF を使用する。
- 透明アクティビティ `IdProviderUiActivity` を用いて下記を処理：
  - `ACTION_OPEN_DOCUMENT_TREE`（推奨：フォルダ選択）または `ACTION_CREATE_DOCUMENT`（ファイル作成）を起動。
  - `takePersistableUriPermission` でツリー/ドキュメント URI に対する永続読書き権限を取得。
  - 取得した URI（ツリー URI）を `SharedPreferences` に保存。

Manifest 例（ライブラリ側）
```xml
<activity
    android:name="com.styly.deviceidprovider.ui.IdProviderUiActivity"
    android:exported="false"
    android:theme="@style/Theme.Transparent.NoActionBar"/>
```

## 4. 保存場所・ファイル（Plus）
- 推奨フォルダ：ユーザーに `Download/Device-ID-Provider/` の選択を案内。
- ファイル名：`device-id-provider.json`
- MIME：`application/json`

## 5. 実装詳細（SAF）
- 初回：
  1) ツリー URI の永続許可が未取得の場合、`IdProviderUiActivity` でドキュメントツリーピッカーを表示。
  2) 選択されたツリー URI を `takePersistableUriPermission` し、`SharedPreferences` に保存。
- 既存/作成：
  - `DocumentFile.fromTreeUri()` でディレクトリ取得。
  - `findFile("device-id-provider.json")` で探索。なければ作成。
  - `ContentResolver.openInputStream`/`openOutputStream` で JSON を読み書き。
- 破損：読取/解析失敗時はログ出力後、新 GUID を生成して上書き保存。

擬似コード（抜粋）
```java
Uri tree = SafHelper.ensureTreeUri(activity); // may show UI once
DocumentFile dir = DocumentFile.fromTreeUri(activity, tree);
DocumentFile file = SafHelper.findOrCreate(dir, "device-id-provider.json", "application/json");
String id = tryReadGuid(resolver, file.getUri());
if (id == null) {
  id = UUID.randomUUID().toString().toLowerCase();
  writeJson(resolver, file.getUri(), jsonOf(id));
}
return success(id);
```

## 6. 共有運用（Plus）
- 各アプリが同一フォルダを選択すれば同一ファイルを共有可能。
- 誤ったフォルダ/ファイル選択による ID 分岐を防ぐため、UI 文言で選択先を明示（`Download/Device-ID-Provider/` を推奨）。

## 7. 動作確認チェックリスト（Plus）
- 2 アプリで同一フォルダを SAF で選択 → 同一 ID が返る。
- 初回の SAF ダイアログ表示と、その後の無人 I/O（永続 URI 再利用）。
- 破損時の再生成・上書きとログ出力。
- キャンセル時のエラーコード/メッセージの受け渡し。

## 8. 既知の制約（Plus）
- 永続権限はアプリ単位。各アプリごとに同一フォルダの選択が必要。
- 端末実装により SAF の挙動やパフォーマンスに差異がある可能性。ロギングを手厚く。

## 9. 公開 API（Plus）
- Base と同一の Java/Unity API を使用（内部で API レベルに応じて MediaStore/SAF を分岐）。

## 10. SAF 選択UIガイダンス（Plus）
- 目的と動作を簡潔に伝える（初回のみ／共有のため）
  - タイトル例: 「共有IDの保存先フォルダを選択してください」
  - 説明例: 「複数アプリで同じIDを利用するため、『Download/Device-ID-Provider』フォルダを選択してください。初回のみの操作です。」
- 推奨選択肢を明示し、誤選択の影響を説明
  - 文言例: 「別の場所を選ぶと、他アプリと異なるIDになります。」
- 成功/失敗時のトーストやログ
  - 成功例: 「保存先を記憶しました。次回以降は自動で利用します。」
  - 失敗例: 「権限が付与されませんでした。再度お試しください。」
- キャンセル時の扱い
  - 文言例: 「選択がキャンセルされました。アプリの機能を利用するには保存先の選択が必要です。」
- 権限の再取得案内
  - 文言例: 「権限が無効化された場合は、再度フォルダを選択してください。」
- UI 実装メモ
  - 透明アクティビティ上でダイアログ/説明ビューを重ねてもよい（端末依存の描画に注意）。
  - 選択ダイアログ起動前に簡単な説明を表示してから SAF を起動すると誤操作が減る。

## 11. 実装保留方針（Plus）
現時点では、明示の指示があるまで Plus（API ≥ 33, SAF）仕様の実装は行わず、エラー表示（コールバック）に留めます。Base（API ≤ 32）は従来どおり実装・有効です。

- 実行時挙動（API ≥ 33）
  - SAF UI の起動や権限取得は行わない。
  - 公開 API `getDeviceId(Activity, Callback)` 呼び出し時、即座に `onError` を呼ぶ。
  - エラーコード: `E_PLUS_DISABLED`
  - メッセージ例: 「API 33+ の SAF 実装は保留中です。指示があるまで利用できません。」
- Unity 側の推奨挙動
  - 受け取ったエラーをユーザーに分かりやすく表示（ダイアログ/トースト/ログ）。
  - 必要に応じて代替案（API 32 以下の端末での動作、もしくは後日再試行）を案内。
- 切替条件（実装再開のトリガ）
  - 指示を受け次第、当セクションを撤回し、5章「実装詳細（SAF）」に従って実装を有効化。
  - 望ましければ機能フラグで制御（例）:
    - `BuildConfig.DEVICE_ID_PROVIDER_PLUS_ENABLED = true/false`
    - もしくは Manifest `meta-data` や ランタイム設定による明示的有効化。
- 受け入れ基準（暫定）
  - API ≥ 33 の環境で `getDeviceId` を呼ぶと、`E_PLUS_DISABLED` が即時に返る。
  - API ≤ 32 の環境では Base 仕様どおりに ID が得られる。
