package com.abei.splitplay.media

/**
 * 引擎实例承载的轨道类型。M2b 双 Player 拆分前,所有引擎都用 [BOTH] —— 跟既有行为一致。
 *
 *  - [BOTH]:全功能 player,音视频都解码。
 *  - [AUDIO_ONLY]:只解音频,RenderersFactory 不创建 video renderer;TrackSelector
 *    再额外禁用 video 轨,double belt-and-braces。即便 HLS 中途切换 track 也不会突然
 *    冒出视频帧。
 *  - [VIDEO_ONLY]:对称,只解视频(可用作静音 MV / HiFi 听歌的视频面)。
 */
enum class PlayerChannel {
    BOTH,
    AUDIO_ONLY,
    VIDEO_ONLY,
}
