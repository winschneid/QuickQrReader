# 改善ログ

`/improve` の自己改善ループが1サイクルごとに追記する。新しいサイクルほど上。
次のサイクルは必ずこのログを読んでから、末尾の「次の候補」を起点にテーマを選ぶ。

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

- **`rawValue` が非 null のまま文字化けするケース**（優先度: 高 / **要実機検証**）
  サイクル2の積み残し。ML Kit が Shift_JIS の QR を ISO-8859-1 として解釈すると、
  null ではなく「読めているが意味不明な文字列」が返る。この場合 `QrTextDecoder` の
  フォールバックは発動しない。着手する前に、実機と実物の Shift_JIS QR で
  「`rawValue` が null になるのか、化けた文字列になるのか」を必ず確認すること。
  確認せずにヒューリスティックを入れると、今読めている QR を壊す。
  併せて `GmsBarcodeScanner` が `rawBytes` を返すかも実機で確認する。

- **`MainActivity` が 750 行超**（優先度: 中）
  QR スキャン / 共有画像処理 / シリアル OCR / Compose 画面がすべて同居している。
  Composable を `ui/` 配下へ、シリアル OCR のカメラ制御を別クラスへ切り出す。
  1サイクル1テーマなので、機能変更とは混ぜずに単独でやる。

- **`Patterns.WEB_URL` が非 ASCII を含む URL に一致しない**（優先度: 低）
  `HandleQrCodeUseCase.looksLikeWebUrl()` の判定で、日本語を含むパスやIDNドメインの
  スキーム無し URL がテキスト共有に落ちる。

- **CI が無い**（優先度: 低）
  GitHub Actions で `:app:testDebugUnitTest` を回せば、このループの検証を PR 上でも担保できる。
