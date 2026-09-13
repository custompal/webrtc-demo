package com.example.webrtcdemo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.webrtcdemo.diag.DiagnosticsScreen
import com.example.webrtcdemo.ui.call.CallScreen
import com.example.webrtcdemo.ui.home.HomeScreen

// ============================================================================
// 导航图（doc/14 §2.1；doc/10 §4）
// ----------------------------------------------------------------------------
//   home                 → HomeScreen（创建 / 加入）
//   call/{roomId}/{role} → CallScreen（role = host | joiner）
//   diagnostics          → DiagnosticsScreen（诊断页，§2.1 diag/）
// 导航动作（doc/10 §4）：
//   创建成功 → navigate("call/$roomId/host")；加入成功 → navigate("call/$roomId/joiner")
//   挂断     → popBackStack("home", inclusive = false)
// ============================================================================

/** 路由常量。 */
object Routes {
    /** 首页。 */
    const val HOME = "home"

    /** 通话页（带两个参数）。 */
    const val CALL = "call/{roomId}/{role}"

    /** 诊断页。 */
    const val DIAGNOSTICS = "diagnostics"

    /** 构造通话页路由。 */
    fun call(roomId: String, role: String): String = "call/$roomId/$role"

    /**
     * 通话结束提示在导航返回时回传给首页的 `savedStateHandle` 键。
     *
     * 用途：通话因终态失败结束时（`ROOM_NOT_FOUND` / 重连耗尽等），叫用户"回到首页 + 明确提示"
     * 而不留下半死不活的通话界面（captain 2026-09-13；提示随后由 [HomeScreen] 展示）。
     */
    const val KEY_CALL_END_NOTICE = "call_end_notice"
}

/**
 * 应用导航宿主（单 Activity）。
 *
 * @param navController 导航控制器（默认 [rememberNavController]）。
 */
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) { entry ->
            // 通话结束提示：由通话页在返回时写入本 entry 的 savedStateHandle，这里读取并展示；
            // 用户点「创建/加入」时清除，避免旧提示残留到下一次通话。
            val callEndNotice by entry.savedStateHandle
                .getStateFlow<String?>(Routes.KEY_CALL_END_NOTICE, null)
                .collectAsStateWithLifecycle()
            HomeScreen(
                onEnterCall = { roomId, role ->
                    entry.savedStateHandle.remove<String>(Routes.KEY_CALL_END_NOTICE)
                    navController.navigate(Routes.call(roomId, role))
                },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                callEndNotice = callEndNotice,
            )
        }

        composable(
            route = Routes.CALL,
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
                navArgument("role") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId").orEmpty()
            val role = backStackEntry.arguments?.getString("role").orEmpty()
            CallScreen(
                roomId = roomId,
                role = role,
                onHangup = { notice ->
                    // 终态失败（notice 非空）→ 交回首页明确提示；正常挂断 notice 为空，不提示
                    notice?.let {
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(Routes.KEY_CALL_END_NOTICE, it)
                    }
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }

        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
    }
}
