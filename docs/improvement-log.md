# 改善ログ

`/improve` の自己改善ループが1サイクルごとに追記する。新しいサイクルほど上。
次のサイクルは必ずこのログを読んでから、末尾の「次の候補」を起点にテーマを選ぶ。

---

## サイクル 5 — 読めなかったときに共有画像経路を案内する (2026-09-22)

**症状 / 動機**: サイクル4で、日本語テキストを含む QR（Shift_JIS バイトモード / Kanji モード）が
カメラ経路では読めず、共有画像経路なら読めることが実機で確定した。
読めない QR に当てたユーザーは、スキャナーが回り続けるだけで何も起きず、
戻る操作で諦めるしかない。**読める方法があることを知る手段が無い。**

**採らなかった選択肢**: カメラ経路を `GmsBarcodeScanner` から CameraX + バンドル版 ML Kit へ
置き換えれば根治する。だが `GmsBarcodeScanner` が提供しているスキャナー UI・オートフォーカス・
オートズーム・トーチをすべて自前で作り直すことになり、さらに**カメラ権限の要求フローが必要になる**。
現状 QR スキャンは権限不要で、起動即スキャンがこのアプリの主な価値なので、これは実質的な機能低下。
日本語テキストの QR にどれだけ遭遇するかのデータが無いまま払うコストとしては大きすぎると判断した。

**原因（案内を出す難しさ）**: `GmsBarcodeScanner.startScan()` は**読み取れたときしか完了しない**。
`addOnFailureListener` は呼ばれず、「読めなかった」というイベントが存在しない。
検知できるのはユーザーのキャンセルだけ。

**変更**:
- `ui/MainViewModel.kt`: スキャナーを開いた時刻を記録し、`onScanCanceled()` で経過時間を見る。
  8秒以上開いた末のキャンセルを「読めなくて諦めた」と解釈して、
  共有画像経路を案内するトーストを出す。開いてすぐ閉じた場合は黙って戻る
  （単に気が変わっただけなので、そこに案内を出すと邪魔なだけ）。
  時刻源はコンストラクタ引数で注入し、テストから制御できるようにした。
- `res/values/strings.xml`: `hint_share_image_instead` を追加。

**閾値 8 秒の根拠**: 無い。「気が変わっただけの人に出さない」ために余裕を持たせた値で、
実使用で調整の余地がある。短くすると誤爆が増え、長くすると読めずに諦めた人に届かない。

**検証**: `MainViewModelTest` に4件追加（計91件）。
長時間後のキャンセルで案内が出ること、即キャンセルでは出ないこと、
読み取り成功後のキャンセルでは出ないこと、スキャン開始なしのキャンセルでは出ないこと。
閾値を 0 にすると2件が落ちることを確認済み。`assembleDebug` も成功。

**未検証**: 実機でトーストが意図どおり出るかは未確認。

---

## サイクル 4 — 実機検証の結果を反映し、サイクル2の記述を訂正 (2026-09-22)

**実機**: Xiaomi 23013PC75G / Android 15。診断ログを一時的に仕込んで
`rawValue` / `rawBytes` / `displayValue` を実測した。

### 分かったこと

1. **`rawValue` は null になる。文字化けではなかった。**
   Shift_JIS（バイトモード）の QR を共有画像経路で読ませた結果:
   `rawValue=NULL  rawBytes=14B hex=89ef88f58fd882cd82b182bf82e7  decoded="会員証はこちら"`
   サイクル2のフォールバックが実機で機能することを確認した。

2. **カメラ経路では Shift_JIS の QR がそもそも認識されない。**
   `GmsBarcodeScanner`（Play 開発者サービス側）は結果を一切返さず、
   ログに1行も出なかった。`startScan()` は成功時しか完了しないため、
   スキャナーが延々と回り続ける。**フォールバックの出番が無い。**
   一方でバンドル版 ML Kit（共有画像経路）は同じ QR を読めている。

3. **`rawValue` が null でも `displayValue` には正しい文字列が入っていた。**

### サイクル2の記述の訂正

サイクル2で「カメラ経路と共有画像経路の両方を直した」と書いたのは**誤り**。
実際に直ったのは**共有画像経路だけ**。カメラ経路は上記2の理由で効果が無い。

同じくサイクル2の「国内の QR には Shift_JIS が珍しくない」も**未検証の思い込み**だった。
根拠はテスト用に自分で生成した QR だけで、実世界の分布のデータは持っていない。
実際には QR の大半は URL（ASCII）で、日本語が入るのは vCard・Wi-Fi の SSID・
プレーンテキストに限られる。**この問題の実利用上の影響は小さい可能性が高い。**

### 追試: Kanji モードの QR（同日）

QR の規格には Shift_JIS 前提の **Kanji モード**があり、日本製の生成ツールが日本語を
入れるときはバイトモードではなくこちらを使う。これをカメラ経路で読めるか追試した。

**結果: カメラ経路では読めない。共有画像経路では読める。**

```
source=image  rawValue=NULL  displayValue="会員証はこちら"
              rawBytes=14B hex=89ef88f58fd882cd82b182bf82e7
              decoded="会員証はこちら"
source=camera （ログ行なし = 結果が返っていない）
```

Kanji モードでも `rawValue` は null で、`rawBytes` に Shift_JIS のバイト列が入る。
**サイクル2のフォールバックが無ければ共有経路でもこの QR は読めない**
（`rawValue` が空のバーコードは候補から捨てられていたため）。

つまりカメラで読めないのは「バイトモードに Shift_JIS バイト列を焼いた」特殊ケースに
限らない。**日本語テキストを含む QR 全般**が `GmsBarcodeScanner` では読めず、
バンドル版 ML Kit なら読める。デコーダーの実装差。

ただし影響範囲は「日本語テキストを含む QR」に限られる。世の中の QR の大半は URL（ASCII）で、
そちらは両経路とも問題なく読める。**どれだけ遭遇するかは依然としてデータが無い。**

### 検証時の注意（再現する人向け）

`qrcode` (npm) は `mode: 'kanji'` を指定しても、Shift_JIS 変換関数を渡さない限り
**黙ってバイトモードに落とす**。例外もログも出ない。
`require('qrcode/helper/to-sjis')` を `toSJISFunc` に渡すこと。
これに気づかず、最初の追試では UTF-8 の QR を Kanji モードだと思って読ませていた
（生成物の MD5 がバイトモード版と一致して発覚）。

### 変更

- `domain/QrTextDecoder.kt`: `displayValue` を**最後の手段**として受け取る。
  `rawValue` → `rawBytes` の順で試し、どちらも駄目なときだけ使う。
  `displayValue` は元の内容が欠けることがある（ML Kit の例:
  `"MEBKM:TITLE:Google;URL://www.google.com;;"` に対して `"//www.google.com"`）ため、
  優先度を最下位に置いている。
- `MainActivity.kt`: 両経路から `displayValue` を渡す。

**検証**: `QrTextDecoderTest` に6件追加（計19件）。優先順位（rawValue > rawBytes > displayValue）と、
空・制御文字だらけの `displayValue` の棄却を確認。実機ログで観測した実際のバイト列を
テストケースに取り込んだ。ユニットテスト 87 件すべて成功、`assembleDebug` も成功。

---

## サイクル 3 — イベントを Flow から状態に移し、取りこぼしを無くす (2026-09-22)

**症状 / 動機**: 「QR を読み取ったのに何も起きない」が起こり得る経路が残っていた。
再現条件が狭いため報告としては上がりにくいが、起きるとユーザーには原因が分からない。

**原因**: `MainViewModel` は `Channel(BUFFERED)` + `receiveAsFlow()` でイベントを流し、
`MainActivity` が `flowWithLifecycle(STARTED)` で購読していた。
`Channel` は MainActivity が STOPPED の間もイベントをバッファするが、
`receiveAsFlow()` には「チャネルから受け取ったがまだ emit していない要素は、
コレクター解除時に失われる」という性質がある。
GMS スキャナーが閉じる前後でライフサイクルが STARTED を跨いでコレクターが張り直されるため、
ちょうどその瞬間のイベントが消え得た。

**変更**:
- `ui/MainViewModel.kt`: `Channel` / `eventFlow` を廃止し、未処理イベントを
  `MainUiState.pendingEvents`（`PendingViewEvent(id, event)` のリスト）として保持する。
  `onEventsHandled(ids)` を呼ばれたときだけ、報告された id のものを消す。
  イベント送出が中断関数でなくなったため `processQrCode` の `viewModelScope.launch` も不要になった。
- `MainActivity.kt`: `repeatOnLifecycle(STARTED)` で `uiState` を購読し、
  未処理イベントを取り出す。**取り出し（`onEventsHandled`）と処理（`handleViewEvent`）の間に
  中断点を置かない。** コルーチンが中断できるのは中断点だけなので、この区間は分割されず、
  「消したのに処理しなかった」も「処理したのに消えていない」も起きない。
  コレクターが解除された場合はイベントが状態に残り、次に STARTED になったとき再配信される。

**検証**: `MainViewModelTest` に4件追加、既存のイベント検証はすべて
「Flow を購読する」から「`uiState.pendingEvents` を覗く」方式へ書き換え。
- `pending event should survive until the activity reports it handled`
- `events should queue in order while unhandled`
- `onEventsHandled should only remove the reported events`
- `onEventsHandled should ignore ids that are not pending`

`onEventsHandled` を「全部消す」に変えると上記のうち2件が落ちることを確認済み。
ユニットテスト 81 件すべて成功、`assembleDebug` も成功。

**残るリスク**: プロセス death では未処理イベントは失われる（`pendingEvents` は
`SavedStateHandle` に載せていない）。Intent を復元して再実行するのは副作用の二重発火に
つながるため、意図的に対象外にしている。

**見送り**: 下の「次の候補」参照。

---

## サイクル 2 — UTF-8 以外の QR を rawBytes から読み直す (2026-09-22)

**症状 / 動機**: Shift_JIS で焼かれた QR コードが「データがありません」「画像に QR コードが
見つかりません」になる。国内で配られる QR には Shift_JIS のものが珍しくない。

**原因**: ML Kit の `Barcode.rawValue` は内容を UTF-8 として解釈できないと null を返す。
`MainActivity.startScanning()` はその null をそのまま `onScanSuccess()` に渡し、
`scanBarcodeFromImage()` は `rawValue` が空のバーコードを候補から捨てていた。
どちらも `Barcode.rawBytes`（生バイト列）を一度も見ていなかった。

**変更**:
- `domain/QrTextDecoder.kt` を新規追加。`rawValue` が空のときだけ `rawBytes` を
  UTF-8 → windows-31j → Shift_JIS の順に**厳密デコード**（不正バイトを `?` で埋めず失敗させる）し、
  制御文字だらけの結果は棄却する。`rawValue` が取れている場合は必ずそれをそのまま返す。
- `MainActivity.kt`: カメラ経路と共有画像経路の両方をこのデコーダー経由に変更。

**設計上の判断**: `rawValue` が非 null でも文字化けしている（ML Kit が ISO-8859-1 として
解釈してしまう）ケースには手を出していない。判定はヒューリスティックにならざるを得ず、
今読めている QR の解釈を壊すリスクがある。今回はアプリが完全に諦めている経路だけを対象にした。

**検証**: `QrTextDecoderTest` を新規追加（13件）。Shift_JIS の日本語・半角カナ・日本語クエリ付き
URL のデコード、`rawValue` 優先の維持、バイナリ/制御文字の棄却を確認。
フォールバック経路を無効化すると 13件中 6件が落ちることを確認済み。
ユニットテスト 77 件すべて成功、`assembleDebug` も成功。

**未検証**: 実機での確認は未実施。特に `GmsBarcodeScanner`（Play 開発者サービス側のスキャナー）が
`rawBytes` を詰めて返すかは端末依存の可能性がある。返さない場合このフォールバックは no-op で、
従来どおりの挙動になるだけで害はない。

**見送り**: 下の「次の候補」参照。

---

## サイクル 1 — 読み取り後の自動再スキャンを止める (2026-09-22)

**症状 / 動機**: QR を読んでリンク先アプリやブラウザが開いた後、戻るとすぐにスキャナーが
また立ち上がる。読み取った内容も履歴も確認できず、「読み取ったのにまた読み取りに戻される」
状態になる。共有画像から起動したときも同じで、カメラを一度も使っていない経路なのに
戻るとカメラが開く。

**原因**: `MainViewModel.onLaunchSucceeded()` が `autoScanOnResume` を `true` に戻していた。
`MainActivity.handleViewEvent()` が `startActivity()` 成功時にこれを呼ぶため、外部アプリから
戻った最初の `onResume` が必ず `startScanning()` を走らせていた。
待機画面にはスキャンボタン・直近の読み取り結果・履歴が揃っているので、
そこに留まるほうが情報が得られる。

**変更**:
- `ui/MainViewModel.kt`: `onLaunchSucceeded()` を廃止し、`onExternalAppLaunched()` に置き換え。
  自動スキャンを再武装せず `false` のままにする。`autoScanOnResume` はコールドスタート時のみ
  `true` になるフラグに意味を限定した。
- `MainActivity.kt`: 呼び出しを差し替え、`onResume` のコメントを実際の条件に合わせて更新。

**検証**: `MainViewModelTest` に3件追加/書き換え。
- `auto scan should stay off for every resume after a handled scan`
- `auto scan should stay off after the scanner was canceled`
- `auto scan should stay off after a shared image was handled`

`onExternalAppLaunched()` を旧挙動（`true`）に戻すと上記のうち2件が落ちることを確認済み。
ユニットテスト 64 件すべて成功、`assembleDebug` も成功。

**見送り**: 下の「次の候補」参照。

---

## 次の候補

- ~~**`rawValue` が非 null のまま文字化けするケース**~~ → サイクル4の実機検証で
  **存在しないことを確認**（`rawValue` は null になる）。着手不要。

- ~~**カメラ経路で日本語テキストの QR が読めない**~~ → サイクル5で**着手しないと判断**。
  代わりに共有画像経路への案内を出す形にした。日本語 QR に頻繁に当たるようなら再検討する。
  判断の材料として以下を残す。

  **カメラ経路で日本語テキストの QR が読めない**（着手保留）
  サイクル4で実測。`GmsBarcodeScanner` は Shift_JIS バイトモードも **Kanji モード**も
  認識せず結果を返さない。バンドル版 ML Kit はどちらも読める。デコーダーの実装差。

  直すならカメラ経路を `GmsBarcodeScanner` から CameraX + バンドル版 ML Kit に置き換える。
  **依存関係の追加は不要**（シリアル OCR 機能のために両方とも既に入っており、
  `SerialCameraScreen` に CameraX + ImageAnalysis を回す実装の雛形もある）。

  代償は大きい。`GmsBarcodeScanner` が提供している以下を自前で用意することになる:
  スキャナー画面の UI、オートフォーカス / オートズーム、トーチ、カメラ権限の要求フロー
  （現状 QR スキャンは権限不要で、これが失われるのは実質的な機能低下）。
  `installModuleIfNeeded()` とモジュール未導入時のエラー画面も不要になる。

  **着手前に判断すること**: 日本語テキストを含む QR にどれだけ遭遇するか。
  URL の QR は現状でも問題なく読める。ここにデータが無いまま大手術をするかどうか。

- **`MainActivity` が 750 行超**（優先度: 中）
  QR スキャン / 共有画像処理 / シリアル OCR / Compose 画面がすべて同居している。
  Composable を `ui/` 配下へ、シリアル OCR のカメラ制御を別クラスへ切り出す。
  1サイクル1テーマなので、機能変更とは混ぜずに単独でやる。

- **`Patterns.WEB_URL` が非 ASCII を含む URL に一致しない**（優先度: 低）
  `HandleQrCodeUseCase.looksLikeWebUrl()` の判定で、日本語を含むパスやIDNドメインの
  スキーム無し URL がテキスト共有に落ちる。

- **CI が無い**（優先度: 低）
  GitHub Actions で `:app:testDebugUnitTest` を回せば、このループの検証を PR 上でも担保できる。
