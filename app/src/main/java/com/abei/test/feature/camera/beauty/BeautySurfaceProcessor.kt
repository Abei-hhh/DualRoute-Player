package com.abei.test.feature.camera.beauty

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * SurfaceProcessor 实现 —— 把摄像头 OES 纹理通过 OpenGL ES 2.0 的单 pass
 * shader 处理(磨皮 + 美白)后渲染到 [SurfaceOutput]。
 *
 * 数据流:
 *   Camera → Surface(SurfaceTexture, OES) → fragment shader → EGLSurface(各 output)
 *
 * 线程模型:
 *   - 所有 GL 调用都在 "BeautyGL" HandlerThread 上。
 *   - [updateParams] 通过 @Volatile 字段从 UI 线程写,GL 线程读,无需锁。
 *   - SurfaceTexture 的 frame-available 通过同一个 handler 回到 GL 线程。
 *
 * 帧丢弃策略:不主动丢。SurfaceTexture.updateTexImage() 本身就只拿最新一帧,
 * GL 渲染慢时,中间帧会被自动覆盖。
 */
class BeautySurfaceProcessor : SurfaceProcessor {

    private val glThread = HandlerThread("BeautyGL").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val glExecutor = Executor { glHandler.post(it) }

    @Volatile private var smooth: Float = 0f
    @Volatile private var whiten: Float = 0f

    // EGL —— 都只在 GL 线程访问
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    // 1x1 pbuffer:在没有任何输出 surface 时,SurfaceTexture.updateTexImage 也需要
    // 一个 current context;每帧渲染时也用它来做 updateTexImage 的临时挂载。
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var programId = 0
    private var locPosition = 0
    private var locTexCoord = 0
    private var locTexMatrix = 0
    private var locSmooth = 0
    private var locWhiten = 0
    private var locTexel = 0

    private var oesTexture = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inputSize = Size(1, 1)
    private val texMatrix = FloatArray(16)
    private val combinedMatrix = FloatArray(16)

    private val outputs = HashMap<SurfaceOutput, OutputContext>()

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD_VERTICES); position(0) }

    private val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD_TEX_COORDS); position(0) }

    init {
        glHandler.post { initEgl() }
    }

    fun updateParams(params: BeautyParams) {
        smooth = params.smooth.coerceIn(0f, 1f)
        whiten = params.whiten.coerceIn(0f, 1f)
    }

    fun release() {
        glHandler.post { releaseEgl() }
        glThread.quitSafely()
    }

    // region SurfaceProcessor 回调

    override fun onInputSurface(request: SurfaceRequest) {
        glHandler.post {
            releaseInput()
            inputSize = request.resolution
            oesTexture = createOesTexture()
            val st = SurfaceTexture(oesTexture).apply {
                setDefaultBufferSize(inputSize.width, inputSize.height)
                setOnFrameAvailableListener({ glHandler.post { drawFrame() } }, glHandler)
            }
            inputSurfaceTexture = st
            val surface = Surface(st)
            inputSurface = surface
            request.provideSurface(surface, glExecutor) {
                glHandler.post {
                    // camera 用完了这块 surface,清理一下 —— 多数情况是因为 unbind 或 rebind
                    if (inputSurfaceTexture === st) {
                        releaseInput()
                    } else {
                        surface.release()
                        st.release()
                    }
                }
            }
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        glHandler.post {
            val surface = output.getSurface(glExecutor) {
                glHandler.post {
                    outputs.remove(output)?.let { ctx ->
                        if (ctx.eglSurface != EGL14.EGL_NO_SURFACE) {
                            EGL14.eglMakeCurrent(
                                eglDisplay,
                                pbufferSurface, pbufferSurface, eglContext,
                            )
                            EGL14.eglDestroySurface(eglDisplay, ctx.eglSurface)
                        }
                    }
                    output.close()
                }
            }
            val eglSurface = createWindowSurface(surface)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                outputs[output] = OutputContext(eglSurface, output.size)
            } else {
                Log.w(TAG, "createWindowSurface failed for output $output")
            }
        }
    }

    // endregion

    // region 渲染

    private fun drawFrame() {
        val st = inputSurfaceTexture ?: return
        if (eglContext == EGL14.EGL_NO_CONTEXT) return

        // 先挂到 pbuffer 上做 updateTexImage,避免没有任何 output 时崩溃
        if (!EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) {
            Log.w(TAG, "eglMakeCurrent(pbuffer) failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return
        }
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        } catch (e: Throwable) {
            Log.w(TAG, "updateTexImage failed", e)
            return
        }

        for ((output, ctx) in outputs) {
            if (ctx.eglSurface == EGL14.EGL_NO_SURFACE) continue
            if (!EGL14.eglMakeCurrent(eglDisplay, ctx.eglSurface, ctx.eglSurface, eglContext)) continue

            GLES20.glViewport(0, 0, ctx.size.width, ctx.size.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            output.updateTransformMatrix(combinedMatrix, texMatrix)
            drawQuad(combinedMatrix)
            EGL14.eglSwapBuffers(eglDisplay, ctx.eglSurface)
        }
    }

    private fun drawQuad(matrix: FloatArray) {
        GLES20.glUseProgram(programId)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(locPosition)
        GLES20.glVertexAttribPointer(locPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(locTexCoord)
        GLES20.glVertexAttribPointer(locTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(locTexMatrix, 1, false, matrix, 0)
        GLES20.glUniform1f(locSmooth, smooth)
        GLES20.glUniform1f(locWhiten, whiten)
        GLES20.glUniform2f(
            locTexel,
            1f / inputSize.width.coerceAtLeast(1),
            1f / inputSize.height.coerceAtLeast(1),
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(locPosition)
        GLES20.glDisableVertexAttribArray(locTexCoord)
    }

    // endregion

    // region EGL / Shader 初始化

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val attrib = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attrib, 0, configs, 0, 1, numConfigs, 0)) {
            "eglChooseConfig failed"
        }
        eglConfig = configs[0]

        val ctxAttrib = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrib, 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val pbAttrib = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttrib, 0)
        check(pbufferSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }

        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
        compileProgram()
    }

    private fun compileProgram() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vs)
        GLES20.glAttachShader(programId, fs)
        GLES20.glLinkProgram(programId)
        val status = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(programId)
            GLES20.glDeleteProgram(programId)
            throw RuntimeException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        locPosition = GLES20.glGetAttribLocation(programId, "aPosition")
        locTexCoord = GLES20.glGetAttribLocation(programId, "aTexCoord")
        locTexMatrix = GLES20.glGetUniformLocation(programId, "uTexMatrix")
        locSmooth = GLES20.glGetUniformLocation(programId, "uSmooth")
        locWhiten = GLES20.glGetUniformLocation(programId, "uWhiten")
        locTexel = GLES20.glGetUniformLocation(programId, "uTexel")
    }

    private fun compileShader(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw RuntimeException("Shader compile failed: $log")
        }
        return id
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex
    }

    private fun createWindowSurface(surface: Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        return EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
    }

    // endregion

    // region 资源释放

    private fun releaseInput() {
        if (oesTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            oesTexture = 0
        }
        inputSurface?.release()
        inputSurface = null
        inputSurfaceTexture?.release()
        inputSurfaceTexture = null
    }

    private fun releaseEgl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
        )
        for ((_, ctx) in outputs) {
            if (ctx.eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, ctx.eglSurface)
            }
        }
        outputs.clear()
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        releaseInput()
        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            pbufferSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    // endregion

    private data class OutputContext(
        val eglSurface: EGLSurface,
        val size: Size,
    )

    companion object {
        private const val TAG = "BeautyProcessor"

        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f,
        )
        // SurfaceTexture 的 transform 矩阵作用于 (0..1, 0..1) 纹理坐标空间,
        // 这里给四个角即可,矩阵自己处理 OES 的旋转/镜像。
        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        // 单 pass 美颜:
        //  1) YCbCr 肤色检测 → skin 软掩码,非肤色像素效果为零,避免整帧发灰
        //  2) 13-tap "surface blur":用 step 判断颜色差,只把和中心颜色相近的邻居
        //     算进均值 —— 廉价 bilateral filter,平滑度远大于原 5-tap 但仍保边
        //  3) 美白只作用于肤色 ∩ 中高亮区,阴影/头发/瞳孔不被洗白
        //
        // 局限:YCbCr 只看颜色,木质家具、米色墙、暖色衣物若色调接近肤色仍会被磨。
        // 想做到真正"只人脸/人体",下一步需接 ML Kit Face Detection 或
        // MediaPipe Selfie Segmentation 给出像素级人像 mask 再相乘。
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            uniform float uSmooth;
            uniform float uWhiten;
            uniform vec2 uTexel;
            varying vec2 vTexCoord;

            float skinMask(vec3 rgb) {
                float Y  =  0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b;
                float Cb = -0.169 * rgb.r - 0.331 * rgb.g + 0.500 * rgb.b + 0.5;
                float Cr =  0.500 * rgb.r - 0.419 * rgb.g - 0.081 * rgb.b + 0.5;
                // Cr 偏红,Cb 偏蓝;经验肤色范围
                float mCr = smoothstep(0.50, 0.54, Cr) * (1.0 - smoothstep(0.61, 0.65, Cr));
                float mCb = smoothstep(0.40, 0.43, Cb) * (1.0 - smoothstep(0.49, 0.52, Cb));
                // Y 放宽到 0.15..0.95,兼容深肤色到强光高光
                float mY  = smoothstep(0.15, 0.28, Y)  * (1.0 - smoothstep(0.92, 0.98, Y));
                return mCr * mCb * mY;
            }

            void main() {
                vec4 c = texture2D(sTexture, vTexCoord);
                float skin = skinMask(c.rgb);

                // Surface blur:13 个采样点,仅和中心颜色差 < 0.10 的算入均值
                const float t = 0.10;
                vec3 sum = c.rgb;
                float wsum = 1.0;
                vec3 s; float w;

                s = texture2D(sTexture, vTexCoord + vec2( 2.0, 0.0) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;
                s = texture2D(sTexture, vTexCoord + vec2(-2.0, 0.0) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;
                s = texture2D(sTexture, vTexCoord + vec2(0.0,  2.0) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;
                s = texture2D(sTexture, vTexCoord + vec2(0.0, -2.0) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;
                s = texture2D(sTexture, vTexCoord + vec2( 2.5,  2.5) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;
                s = texture2D(sTexture, vTexCoord + vec2(-2.5,  2.5) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;
                s = texture2D(sTexture, vTexCoord + vec2( 2.5, -2.5) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;
                s = texture2D(sTexture, vTexCoord + vec2(-2.5, -2.5) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;
                s = texture2D(sTexture, vTexCoord + vec2( 5.0, 0.0) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;
                s = texture2D(sTexture, vTexCoord + vec2(-5.0, 0.0) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;
                s = texture2D(sTexture, vTexCoord + vec2(0.0,  5.0) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;
                s = texture2D(sTexture, vTexCoord + vec2(0.0, -5.0) * uTexel).rgb; w = step(length(s - c.rgb), t); sum += s * w; wsum += w;

                vec3 blurred = sum / wsum;

                // 磨皮 = 仅肤色像素混入 blur 结果
                vec3 smoothed = mix(c.rgb, blurred, uSmooth * skin);

                // 美白 = 肤色 ∩ 中高亮区域,避免阴影 / 头发被洗灰
                float luma = dot(smoothed, vec3(0.299, 0.587, 0.114));
                float lightZone = smoothstep(0.25, 0.85, luma);
                vec3 whitened = mix(smoothed, vec3(1.0), uWhiten * skin * lightZone * 0.35);

                gl_FragColor = vec4(whitened, c.a);
            }
        """
    }
}
