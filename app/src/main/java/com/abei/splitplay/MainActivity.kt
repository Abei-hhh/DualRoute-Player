package com.abei.splitplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.abei.splitplay.navigation.AppNavHost
import com.abei.splitplay.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .components { add(VideoFrameDecoder.Factory()) }
                .crossfade(true)
                .build()
        )
        setContent {
            AppTheme {
                AppNavHost()
            }
        }
    }
}
