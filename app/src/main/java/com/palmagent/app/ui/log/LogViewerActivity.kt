package com.palmagent.app.ui.log

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.palmagent.app.R
import com.palmagent.app.agent.AgentLogger
import com.palmagent.app.ui.viewmodel.LogViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels
import java.io.File

@AndroidEntryPoint
class LogViewerActivity : ComponentActivity() {

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnClear: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnCopyLog: ImageButton
    // v2 优化：路径显示 + 打开目录 + 分享
    private lateinit var tvLogPath: TextView
    private lateinit var btnOpenLogDir: ImageButton
    private lateinit var btnShareLog: ImageButton
    // LIVE 呼吸点
    private lateinit var viewLiveDot: View
    private var liveDotAnimator: ObjectAnimator? = null

    private val viewModel: LogViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        logTextView = findViewById(R.id.logTextView)
        scrollView = findViewById(R.id.logScrollView)
        btnClear = findViewById(R.id.btnClearLog)
        btnBack = findViewById(R.id.btnBack)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        tvLogPath = findViewById(R.id.tvLogPath)
        btnOpenLogDir = findViewById(R.id.btnOpenLogDir)
        btnShareLog = findViewById(R.id.btnShareLog)
        viewLiveDot = findViewById(R.id.viewLiveDot)

        logTextView.movementMethod = ScrollingMovementMethod()

        // LIVE 呼吸动画：alpha 0.3 ↔ 1.0 循环，模拟实时监控心跳
        startLiveDotAnimation()

        btnClear.setOnClickListener {
            viewModel.clearLogs()
            logTextView.text = ""
        }

        btnBack.setOnClickListener { finish() }

        btnCopyLog.setOnClickListener {
            val text = viewModel.copyAllLogs()
            if (text.isBlank()) {
                Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                val clip = android.content.ClipData.newPlainText("PalmAgent Log", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "已复制 ${text.length} 字符", Toast.LENGTH_SHORT).show()
            }
        }

        // v2 优化：刷新日志路径
        refreshLogPathDisplay()
        // 打开日志目录
        btnOpenLogDir.setOnClickListener { onOpenLogDir() }
        // 分享当前 agent_full.log
        btnShareLog.setOnClickListener { onShareLog() }

        scrollView.viewTreeObserver.addOnScrollChangedListener {
            val scrollY = scrollView.scrollY
            val maxScroll = (scrollView.getChildAt(0)?.height ?: 0) - scrollView.height
            viewModel.setAutoScroll(scrollY >= maxScroll - 100)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // logText 现在是 CharSequence（Spannable），可直接赋值保留颜色 span
                    logTextView.text = state.logText
                    if (state.autoScroll) {
                        logTextView.post {
                            val lineCount = logTextView.lineCount
                            if (lineCount > 0) {
                                val scrollAmount = (logTextView.layout?.getLineTop(lineCount) ?: 0) - scrollView.height
                                if (scrollAmount > 0) scrollView.scrollTo(0, scrollAmount)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 启动 LIVE 指示点的呼吸动画（alpha 0.3 ↔ 1.0，时长 1000ms 循环）。
     */
    private fun startLiveDotAnimation() {
        liveDotAnimator = ObjectAnimator.ofFloat(viewLiveDot, View.ALPHA, 0.3f, 1.0f).apply {
            duration = 1000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startAutoRefresh()
        refreshLogPathDisplay()
        // 回到前台时恢复呼吸动画
        if (liveDotAnimator == null || !liveDotAnimator!!.isRunning) {
            startLiveDotAnimation()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopAutoRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 销毁时停止动画，避免泄漏
        liveDotAnimator?.cancel()
        liveDotAnimator = null
    }

    // ============ v2 优化：路径显示 + 打开目录 + 分享 ============
    private fun refreshLogPathDisplay() {
        val path = AgentLogger.getCurrentLogDir() ?: AgentLogger.getCurrentLogFilePath()
        tvLogPath.text = if (path.isNullOrBlank()) "无任务日志（请先执行任务）" else "📁 $path"
    }

    private fun onOpenLogDir() {
        val logPath = AgentLogger.getCurrentLogFilePath()
        if (logPath.isNullOrBlank() || !File(logPath).exists()) {
            Toast.makeText(this, "暂无日志文件，请先执行任务", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(logPath).parentFile ?: return
        // 优先 SAF 打开目录
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(
            FileProvider.getUriForFile(this, "$packageName.fileprovider", dir),
            "*/*"
        )
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // 没装文件管理器：提示路径
            Toast.makeText(this, "文件路径：\n${dir.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onShareLog() {
        val logPath = AgentLogger.getCurrentLogFilePath()
        if (logPath.isNullOrBlank() || !File(logPath).exists()) {
            Toast.makeText(this, "暂无日志文件，请先执行任务", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val file = File(logPath)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.putExtra(Intent.EXTRA_SUBJECT, "PalmAgent 任务日志 - ${file.parentFile?.name}")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "分享日志"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
