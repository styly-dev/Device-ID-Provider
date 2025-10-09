# Unity/Android Device ID Provider 仕様（Base: API ≤ 32, MediaStore, Java AAR + Unity Wrapper）

## 1. 目的
- Unity から利用可能な Android ライブラリ（AAR, Java 実装）として、端末固有の GUID を生成・保持・共有する仕組みを提供する。
- 本仕様は API 32 以下（Android 12L まで）での確実動作を対象とし、MediaStore（Downloads）を使用する。
- 公開 API は Unity 側から非同期で呼び出せる `GetDeviceID()` 相当（Java 側 `getDeviceId(Activity, Callback)`）。

## 2. 前提・範囲
- 保存場所（MediaStore Downloads）
  - `RELATIVE_PATH`: `Download/Device-ID-Provider/`
  - `DISPLAY_NAME`: `device-id-provider.json`
  - `MIME`: `application/json`
- JSON 形式：
  ```json
  { "device-id": "<guid-lowercase-hyphenated>" }
  ```
- GUID 生成：`UUID.randomUUID().toString().toLowerCase()`（Java）。
- 対象デバイス：Pico / Meta Quest 系（Android ベース）。
- 実装言語：Java（Android ライブラリ/AAR）。Unity 側は C# ラッパのみ。

## 3. 受け入れ基準（API ≤ 32）
- `GetDeviceID()` は同一端末で常に同じ ID を返す（共有 JSON の値）。
- JSON が無い場合、新規 GUID を生成・保存し、その値を返す。
- 複数アプリで同一端末・同一 JSON を参照できる（MediaStore 経由で横断）。
- 初回のみのランタイム許可ダイアログ（`READ_EXTERNAL_STORAGE`）は許容。
- 競合を避け、二重作成を最小化。発生時は先行ファイルを採用。
- JSON 破損時はエラーログ出力の上で新 GUID を再生成・上書き保存。

## 4. Android 設計（API レベル）
- 最小 API：29（Android 10）。
- 動作保証：API 29–32（MediaStore）。
- API 29–30 は作成時に `IS_PENDING=1` を付与し、書き込み後に `IS_PENDING=0` に更新。

## 5. 権限ポリシー（API ≤ 32）
- 読み取り：`READ_EXTERNAL_STORAGE` を初回のみランタイム要求。
- 書き込み：MediaStore 経由で原則不要（挿入時に許可される）。

## 6. アーキテクチャ（Java + Unity）
- Java ライブラリ（AAR）`com.styly.deviceidprovider`：
  - `DeviceIdProvider`（公開ファサード: `getDeviceId(Activity, Callback)`）
  - `MediaStoreHelper`（MediaStore I/O 実装）
  - `PermissionHelper`（権限要求ヘルパ）
  - `Json`（最小 JSON 解析ユーティリティ）
- Unity C# ラッパ：
  - `DeviceIdProviderUnity.GetDeviceID(Action<string>, Action<string,string>)`
  - Java の `Callback` を `AndroidJavaProxy` で受領。

## 7. 実装詳細（MediaStore）
- 検索条件：
  - `RELATIVE_PATH = "Download/Device-ID-Provider/"`
  - `DISPLAY_NAME = "device-id-provider.json"`
- 読み出し：`openInputStream` → UTF-8 → JSON 解析 → `device-id`。
- 作成：GUID 生成 → `insert()`（必要に応じ `IS_PENDING=1`）→ JSON 書込み → `IS_PENDING=0` 更新。
- 破損：読取失敗/解析失敗時はログ出力後、新 GUID で上書き。

擬似コード（抜粋）
```java
String id = peekDeviceId(context);
if (id != null) return success(id);
String newId = UUID.randomUUID().toString().toLowerCase();
Uri uri = insertJson(resolver, RELATIVE_PATH, DISPLAY_NAME);
writeJson(resolver, uri, jsonOf(newId));
completePendingIfNeeded(resolver, uri);
return success(newId);
```

## 8. 動作確認チェックリスト（Base）
- 既存 JSON あり → 同じ ID を返す。
- JSON なし → 新規生成・保存・返却。
- 2 アプリ間で横断参照できる（同じ ID が返る）。
- 初回 READ 権限ダイアログの表示と再試行が正しく動作する。
- 同時初回呼び出しで二重作成が起きない／起きても参照は一意。
- UTF-8 正常（文字化けなし）。
- 破損時にログ出力と再生成・上書きが行われる。

## 9. 既知の制約（Base）
- ベンダー実装差異により `RELATIVE_PATH` 取り扱いや権限挙動が異なる可能性。ログを手厚く。
- 本仕様は API 33+（Android 13+）を対象にしない。33+ は別紙（spec_plus.md）を参照。

## 10. 公開 API 例（Java / Unity）
- Java
```java
public final class DeviceIdProvider {
  public interface Callback { void onSuccess(String id); void onError(String code, String message); }
  public static void getDeviceId(@NonNull Activity activity, @NonNull Callback cb);
}
```
- Unity
```csharp
DeviceIdProviderUnity.GetDeviceID(
  id => Debug.Log($"DeviceID: {id}"),
  (code, msg) => Debug.LogError($"{code}: {msg}")
);
```

## 11. 納品物（Base）
- Android ライブラリ（AAR）: `DeviceIdProvider`, `MediaStoreHelper`, `PermissionHelper`, `Json`, Manifest, Gradle
- Unity C# ラッパ: `DeviceIdProviderUnity.cs`
- サンプルシーン: `Demo.scene`（任意）
- README（ビルド／設置手順、既知の制約）

