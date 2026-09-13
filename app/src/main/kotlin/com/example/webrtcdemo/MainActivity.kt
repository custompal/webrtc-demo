package com.example.webrtcdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.ui.navigation.AppNavHost
import com.example.webrtcdemo.ui.theme.WebRtcDemoTheme

// ============================================================================
// 单 Activity 入口（doc/14 §2.1/§7.6；doc/10 §4）
// ----------------------------------------------------------------------------
// 职责：应用 Material3 主题 + 挂载 [AppNavHost]（home / call/{roomId}/{role} / diagnostics）。
// 日志与 native 日志初始化已在 `WebRtcDemoApp.onCreate` 完成（§9.3/§9.4），此处不重复。
// Activity 冻结项（§7.6）：exported=true、screenOrientation=portrait（见 AndroidManifest.xml）。
// ============================================================================

/**
 * 应用唯一 Activity。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(MODULE_TAG, "main_activity_create")

        setContent {
            WebRtcDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavHost()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.i(MODULE_TAG, "main_activity_resume")
    }

    override fun onPause() {
        AppLog.i(MODULE_TAG, "main_activity_pause")
        super.onPause()
    }

    /** §9.1 模块标签（UI 层固定 ui）。 */
    private companion object {
        const val MODULE_TAG = "ui"
    }
}
