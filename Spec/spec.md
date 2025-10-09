Unity/Android Device ID Provider 指示書（MediaStore／C#のみ）
1) 目的
Unity C# のみで Android 端末固有の GUID を生成・保持・共有する仕組みを実装する。
GUID は端末の MediaStore の Downloads コレクション配下に置く JSON ファイルを単一ソースとして共有（複数アプリ間で同一値を参照）する。
公開 API は GetDeviceID() のみ。初回呼び出し時に JSON が無ければ生成して保存、以後は読み出しを返す。
2) 前提・範囲
保存場所: Downloads/Device-ID-Provider/device-id-provider.json
JSON 形式:
{ "device-id": "<GUID (lowercase hyphenated) >" }
GUID: System.Guid.NewGuid().ToString("D").ToLowerInvariant() を使用。ファイルが削除されるまで不変。
対象デバイス: Pico / Meta Quest 系（Android ベース）
実装言語: Unity C# のみ（ネイティブ AAR なし）。Android API は AndroidJavaObject/AndroidJavaClass 経由で呼び出す。
備考: Downloads はスコープドストレージ配下の共有領域。MediaStore を用いて作成・検索・入出力を行う。
3) 動作要件（受け入れ基準）
GetDeviceID() は 常に同じ文字列（当該 JSON の内容）を返すこと。
JSON が存在しない場合、新規 GUID を生成し、指定パスに JSON を 作成、その値を返すこと。
複数アプリで同一端末・同一 JSON を参照できること。
競合を避け、二重作成を最小化する（後述ロジック）。二重作成が起きた場合でも、以後は 先に存在するファイルを参照すること。
例外（権限未許可、ストレージ不可、I/O エラー等）時は 明確なログを出し、GetDeviceID() は null もしくは例外で呼び出し元に伝播（プロジェクト方針に合わせ選択・実装）。
4) Android 設計ポイント（API レベル別）
推奨最小 API: 29 (Android 10)
実運用ターゲット: Quest/Pico の OS に合わせて API 29–32 を第一優先で動作保証。
API 33+（Android 13+）の注意: Downloads 内の 非メディア(JSON) を他アプリから読む場合、従来の READ_EXTERNAL_STORAGE が置換/縮小されており挙動が端末実装に依存します。UI 介在の SAF を使わず C# のみで完全無人アクセスを保証するには、API 32 以下をターゲットとするか、端末ベンダの権限実装に依存する可能性があります（詳細は質問セクション参照）。
5) 権限・Manifest 方針
API ≤ 32:
READ_EXTERNAL_STORAGE（必須: 他アプリ作成の Downloads 既存 JSON を読む可能性があるため）
WRITE_EXTERNAL_STORAGE（API < 29 でのフォールバック作成用）
API 29–32: MediaStore での作成は原則追加権限不要だが、他アプリ作成分を読むために READ が必要となるケースがあるため、実装ではランタイム許可要求を備える。
API 33+: 非メディア(JSON)は READ_MEDIA_* の対象外。無人で Downloads を横断参照する確実な手段は限定的。対応方針は要確認（#11 参照）。
Unity でのランタイム権限要求: UnityEngine.Android.Permission.RequestUserPermission() を使用。
Manifest の最終確定はビルド設定に依存。Unity の Plugins/Android/AndroidManifest.xml に定義し、必要に応じて権限分岐（maxSdkVersion 等）を加える。
6) 実装設計（MediaStore 中心）
6.1 公開 API（唯一の外部エントリ）
public static class DeviceIdProvider
{
    // Android 以外（Editor/他プラットフォーム）は null or 一時 GUID で可（要件外）。
    public static string GetDeviceID();
}
6.2 コアロジック概観
SDK レベル判定: android.os.Build$VERSION.SDK_INT を取得。
ContentResolver 取得: UnityPlayer.currentActivity.getContentResolver()。
既存 JSON 検索（API ≥ 29: MediaStore.Downloads）
URI: MediaStore.Downloads.EXTERNAL_CONTENT_URI
検索条件（例）:
_display_name = 'device-id-provider.json'
relative_path LIKE 'Download/Device-ID-Provider/%'（末尾スラッシュの差異に注意）
取得カラム: _id, _display_name, relative_path（mime_type も任意）
見つかった場合:
ContentUris.withAppendedId で content:// URI を得て、openInputStream→ JSON 解析 → device-id を返す。
見つからない場合（新規作成）:
ContentValues を構築：
MediaStore.MediaColumns.DISPLAY_NAME = "device-id-provider.json"
MediaStore.MediaColumns.MIME_TYPE = "application/json"
MediaStore.MediaColumns.RELATIVE_PATH = "Download/Device-ID-Provider/"
API 29–30: MediaStore.MediaColumns.IS_PENDING = 1 → 書込完了後に 0 へ更新
insert() → 返却 URI に openOutputStream() して JSON を UTF-8 で書込。
書込後、API 29–30 のみ IS_PENDING = 0 へ update()。
生成した GUID を返却。
API < 29 フォールバック（必要時のみ）:
Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS) 直下で Device-ID-Provider を作成し、File I/O。
ランタイムで WRITE/READ_EXTERNAL_STORAGE 許可必須。
6.3 二重作成の低減（競合制御）
MediaStore は「存在しないこと」を前提とした 原子的 Create-if-absent を保証しないため、以下でリスク低減：
生成前に必ず 再検索。
insert() が失敗/例外 → 即時再検索し、見つかった方を採用。
同時生成で複数ファイルができた場合：
**最初に作られた（最も古い date_added）**を採用し、余剰は読み飛ばす（削除はしない）。
6.4 文字コード／改行
UTF-8 / LF 固定。
6.5 例外処理・リトライ
SecurityException（権限不足）、FileNotFoundException、IOException を明確化してログ出力。
一時的エラーは 1 回までリトライ。リトライ後も失敗時は上位へ例外送出 or null 返却（プロダクト方針に合わせて切替）。
6.6 Unity:左右矢印:Android 橋渡し（JNI）
必須ブリッジ（全て C# で実装）
int GetSdkInt()
AndroidJavaObject GetActivity() / AndroidJavaObject GetContentResolver()
AndroidJavaObject GetDownloadsUri()（MediaStore.Downloads.EXTERNAL_CONTENT_URI）
AndroidJavaObject QuerySingleFile(resolver, uri, displayName, relativePathLike) → cursor
AndroidJavaObject InsertFile(resolver, uri, displayName, mime, relativePath, isPending) → contentUri
Stream OpenInputStream(resolver, contentUri) → C# 側で読取
Stream OpenOutputStream(resolver, contentUri) → C# 側で書込
void UpdateIsPending(resolver, contentUri, 0)（API 29–30）
InputStream/OutputStream 読み書きは JNI 経由で read(byte[])/write(byte[]) をループ。using/finally で確実に close()。
7) 実装タスク（ステップバイステップ）
定数定義：
const string Folder = "Download/Device-ID-Provider/";
const string FileName = "device-id-provider.json";
const string Mime = "application/json";
ブリッジ Util 実装（AndroidBridge.cs）
SDK 取得、Activity/Resolver 取得、URI/Query/Insert/Update/Streams ラッパ。
JSON シリアライズ（JsonUtility もしくは System.Text.Json 相当）
DTO: class DeviceIdDto { public string device_id; }（※ JSON は device-id キー、シリアライズ時にカスタム名対応）
検索ロジック（FindExisting()）
MediaStore をクエリし、先頭 1 件の contentUri を返す。
読取ロジック（TryReadGuid(uri, out string guid)）
openInputStream→ UTF-8 読込 → JSON 解析 → device-id。
作成ロジック（CreateAndWrite()）
GUID 生成 → insert()（IS_PENDING=1 付与: API 29–30）→ JSON 書込 → （必要に応じ IS_PENDING=0 更新）→ 返却。
公開 API（GetDeviceID()）
パーミッション確認（必要な場合のみ要求）→ FindExisting() → あれば TryReadGuid()、無ければ CreateAndWrite()。
失敗時のログ・例外取り扱いを統一。
スレッド安全性
GetDeviceID() の アプリ内多重呼び出しに対して lock ガード。
フォールバック（API <29）
Downloads 直下に Device-ID-Provider フォルダを File で作成し、同名 JSON を I/O。
最低限のデモ
Demo.scene にボタン GetDeviceID と結果表示 Text。
8) 動作確認チェックリスト
 既存 JSON あり → 同じ ID を返す。
 JSON なし → 新規生成・保存・返却。
 2 本のアプリ（A と B）で片方が作成 → もう片方が 読み出せる。
 権限未許可時の挙動（ダイアログ表示・再試行）。
 同時起動（A・B 同時に初回 GetDeviceID）で 二重作成が起きない/起きても参照は一意。
 文字化けなし（UTF-8）。
 JSON 破損時の再生成（許容するかは要件次第：デフォルトは再生成せず失敗扱いを推奨）。
9) 既知の制約と注意
API 33+（Android 13+）：Downloads の 非メディア(JSON) をユーザー操作なしで他アプリから横断参照する可否は端末実装に依存。C# のみで SAF を使わず実装する要件の場合、API 32 までを前提とする運用が安全です。
ベンダー実装（Pico/Quest）により relative_path の扱いや権限の厳格さが異なる場合あり。ロギングを手厚く。
10) 参考コード（最小骨子・疑似）
実際の JNI 呼び出し・ストリーム処理はプロジェクトのユーティリティに合わせて実装してください。
public static class DeviceIdProvider
{
    const string Folder = "Download/Device-ID-Provider/";
    const string FileName = "device-id-provider.json";

    public static string GetDeviceID()
    {
        if (Application.platform != RuntimePlatform.Android)
            return null; // 要件外

        using var activity = AndroidBridge.GetActivity();
        using var resolver = AndroidBridge.GetContentResolver(activity);
        using var downloads = AndroidBridge.GetDownloadsUri();

        // （必要時）権限要求
        AndroidBridge.EnsurePermissions();

        var existing = AndroidBridge.QuerySingle(resolver, downloads, FileName, Folder);
        if (existing != null)
        {
            if (AndroidBridge.TryReadGuid(resolver, existing, out var guid))
                return guid;
        }

        var newGuid = System.Guid.NewGuid().ToString("D").ToLowerInvariant();
        var uri = AndroidBridge.InsertJsonFile(resolver, downloads, FileName, Folder, pending:true);
        AndroidBridge.WriteJson(resolver, uri, $"{{\"device-id\":\"{newGuid}\"}}\n");
        AndroidBridge.CompletePendingIfNeeded(resolver, uri);
        return newGuid;
    }
}
11) 確認したい事項（ご回答ください）
対応 API レベル：対象デバイス（Pico/Quest）での minSdkVersion / targetSdkVersion を指定してください。特に target 33+ の可否。
権限ポリシー：READ_EXTERNAL_STORAGE（API ≤32）など、ランタイム許可の提示は許容されますか？（初回のみ）
API 33+ を対象に含める場合：Downloads の JSON を ユーザー介入なしで他アプリから読む要件は厳しいです。次の選択肢のどれを採用しますか？
① API 32 以下での運用前提（推奨・最小実装）
② JSON を 画像/音声等のメディア種別に偽装（非推奨）
③ 初回のみ SAF（ストレージアクセスフレームワーク）でユーザーにフォルダ選択してもらい、得た URI を永久保存（C# だけでも可能だが UI が必要）
ファイル名とキー名の厳密性：表記の大文字小文字は固定で良いですか？（Device-ID-Provider, device-id-provider.json, "device-id"）
異常系方針：JSON 破損時は 再生成しますか？それとも エラー返却にしますか？
12) 納品物
DeviceIdProvider.cs（公開 API 実装）
AndroidBridge.cs（JNI ユーティリティ）
Demo.scene（ボタン・テキストで動作デモ）
README.md（ビルド設定、Manifest 権限、既知の制約）
テスト観点チェックリスト（本書 #8）