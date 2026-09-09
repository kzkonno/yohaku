package jp.yohaku.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Displays the Health Connect permission rationale inside the app without network access. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("睡眠データの取り扱い", style = MaterialTheme.typography.headlineSmall)
                        Text("Yohakuは、自由時間の見積もりと睡眠記録の補正のため、許可された睡眠の開始・終了時刻と提供元アプリをHealth Connectから読み取ります。睡眠ステージや心拍数は保存しません。")
                        Text("接続後は、アプリを開いたとき・戻ったときに直近29日間を同期します。アプリを閉じている間のバックグラウンド同期は行いません。")
                        Text("取得した記録と手動修正は、この端末のアプリ専用領域に保存します。外部サーバーや広告サービスへの送信、Health Connectへの書き戻しは行いません。修正した記録は再同期でも保持します。")
                        Text("Pixel Watchなどの睡眠を取り込むには、計測元のアプリからHealth Connectへの睡眠の書き込みを有効にし、その後、Yohakuに睡眠の読み取りを許可してください。データが反映される時刻は計測元の同期によります。")
                        Text("Apple Watchなど別端末の睡眠は手入力できます。Appleヘルスケアからの直接同期には対応していません。")
                        Text("停止ボタンで自動入力を止められます。読み取り許可はAndroidのHealth Connect設定から取り消せます。取得記録の非表示は元データの削除ではありません。保存した睡眠データを全て消す場合は、Androidのアプリ設定からYohakuのストレージを消去するか、アプリをアンインストールしてください（予定・条件も消去されます）。")
                        Text("自由時間は睡眠枠と生活予定からの見積もりです。睡眠枠には途中の覚醒が含まれます。未取得の部分には予定を使い、測定済みとは表示しません。")
                        Button(onClick = { finish() }) { Text("戻る") }
                    }
                }
            }
        }
    }
}
