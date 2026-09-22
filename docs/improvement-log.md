# 改善ログ

`/improve` の自己改善ループが1サイクルごとに追記する。新しいサイクルほど上。
次のサイクルは必ずこのログを読んでから、末尾の「次の候補」を起点にテーマを選ぶ。

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

- **Shift_JIS など UTF-8 以外の QR を取りこぼす**（優先度: 高）
  `MainActivity.startScanning()` は `barcode.rawValue` だけを見ており、
  `scanBarcodeFromImage()` も `rawValue` が空のものを捨てている。ML Kit の `rawValue` は
  UTF-8 としてデコードできないと null になるため、Shift_JIS で焼かれた日本語 QR が
  「データがありません」になる。`barcode.rawBytes` を Shift_JIS(MS932) で解釈する
  フォールバックを入れる。バイト列 → 文字列の純粋関数に切り出せばテストしやすい。

- **`flowWithLifecycle` + `receiveAsFlow` のイベント取りこぼしリスク**（優先度: 中）
  `Channel.receiveAsFlow()` は、受信済みで未 emit の要素をコレクター解除時に失う。
  GMS スキャナーが閉じる前後で `flowWithLifecycle(STARTED)` がコレクターを張り直すため、
  `StartActivity` イベントが消えて「読み取ったのに何も起きない」になり得る。
  状態として保持し、処理後に明示的に消費する方式を検討する。

- **`MainActivity` が 733 行**（優先度: 中）
  QR スキャン / 共有画像処理 / シリアル OCR / Compose 画面がすべて同居している。
  Composable を `ui/` 配下へ、シリアル OCR のカメラ制御を別クラスへ切り出す。
  1サイクル1テーマなので、機能変更とは混ぜずに単独でやる。

- **`Patterns.WEB_URL` が非 ASCII を含む URL に一致しない**（優先度: 低）
  `HandleQrCodeUseCase.looksLikeWebUrl()` の判定で、日本語を含むパスやIDNドメインの
  スキーム無し URL がテキスト共有に落ちる。

- **CI が無い**（優先度: 低）
  GitHub Actions で `:app:testDebugUnitTest` を回せば、このループの検証を PR 上でも担保できる。
