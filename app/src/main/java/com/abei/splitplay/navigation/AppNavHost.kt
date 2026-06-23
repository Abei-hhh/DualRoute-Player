package com.abei.splitplay.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.abei.splitplay.feature.camera.CameraRecorderScreen
import com.abei.splitplay.feature.gallery.VideoGalleryScreen
import com.abei.splitplay.playerui.PlayerScreen

object Routes {
    const val GALLERY = "gallery"
    const val PLAYER = "player/{uri}"
    const val CAMERA = "camera"
    fun player(uri: String) = "player/${java.net.URLEncoder.encode(uri, "UTF-8")}"
}

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.GALLERY,
        modifier = modifier,
    ) {
        composable(Routes.GALLERY) {
            VideoGalleryScreen(
                onVideoClick = { uri ->
                    navController.navigate(Routes.player(uri.toString()))
                },
                onPickFromFile = { uri ->
                    navController.navigate(Routes.player(uri.toString()))
                },
                onOpenCamera = { navController.navigate(Routes.CAMERA) },
            )
        }
        composable(Routes.CAMERA) {
            CameraRecorderScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument("uri") { type = NavType.StringType }),
            // 默认 700ms fade 会让 SurfaceView 在淡入/淡出时短暂可见但还没绑定到 ExoPlayer,
            // 进入是黑屏一闪、返回时画面又"复活"一下。直接把过渡关掉,瞬时切换看不到瑕疵。
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) { backStackEntry ->
            // Navigation Compose 已自动对 path 参数做一次 URL 解码，不要再手动 decode。
            val decodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            val uri = android.net.Uri.parse(decodedUri)
            PlayerScreen(
                initialUri = uri,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
