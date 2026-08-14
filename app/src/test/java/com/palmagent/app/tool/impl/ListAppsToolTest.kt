package com.palmagent.app.tool.impl

import com.palmagent.app.AgentApplication
import com.palmagent.app.utils.InstalledAppProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic

/**
 * ListAppsTool 单元测试（纯 JVM）
 *
 * 验证目标：
 * 1. execute() 全量查询：返回设备所有已装应用
 * 2. execute(keyword=) 模糊过滤：按应用名过滤
 * 3. execute(max_results=N) 截断：不超过上限
 * 4. 缓存为空时返回错误（"缓存未就绪"）
 *
 * 设计要点：
 * - 反射注入 InstalledAppProvider.installedAppsCache（参考 PlannerServiceTest）
 * - 反射注入 AgentApplication.instance 为 mock（避免 null）
 */
class ListAppsToolTest {

    private lateinit var listAppsTool: ListAppsTool
    private val mockAgentApplication: AgentApplication = Mockito.mock(AgentApplication::class.java)
    private var originalInstance: AgentApplication? = null
    private var originalAccessible: Boolean = false

    @Before
    fun setUp() {
        // Kotlin 编译将 Companion 的 lateinit var 提升为外部类的 private static 字段 "instance"
        val instanceField = AgentApplication::class.java.getDeclaredField("instance")
        originalAccessible = instanceField.isAccessible
        instanceField.isAccessible = true
        originalInstance = instanceField.get(null) as? AgentApplication
        instanceField.set(null, mockAgentApplication)

        listAppsTool = ListAppsTool()
    }

    @After
    fun tearDown() {
        try {
            val instanceField = AgentApplication::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            if (originalInstance == null) {
                instanceField.set(null, null)
            } else {
                instanceField.set(null, originalInstance)
            }
        } catch (_: Exception) { /* ignore */ }
        clearInstalledAppsCache()
    }

    @Test
    fun `execute 全量查询_应返回所有已装应用`() = runBlocking {
        injectInstalledAppsCache(
            mapOf(
                "微信" to "com.tencent.mm",
                "高德地图" to "com.autonavi.minimap",
                "支付宝" to "com.eg.android.AlipayGphone"
            )
        )

        val result = listAppsTool.execute(emptyMap())

        assertTrue("execute 应成功: ${result.error}", result.isSuccess)
        val data = result.data ?: ""
        assertTrue("应包含微信 → com.tencent.mm: $data", data.contains("微信 → com.tencent.mm"))
        assertTrue("应包含高德地图 → com.autonavi.minimap: $data", data.contains("高德地图 → com.autonavi.minimap"))
        assertTrue("应包含支付宝 → com.eg.android.AlipayGphone: $data", data.contains("支付宝 → com.eg.android.AlipayGphone"))
        assertTrue("应注明共 3 个: $data", data.contains("共 3 个"))
    }

    @Test
    fun `execute 关键词过滤_应只返回匹配项`() = runBlocking {
        injectInstalledAppsCache(
            mapOf(
                "微信" to "com.tencent.mm",
                "高德地图" to "com.autonavi.minimap",
                "支付宝" to "com.eg.android.AlipayGphone"
            )
        )

        val result = listAppsTool.execute(mapOf("keywords" to listOf("地图")))

        assertTrue("execute 应成功: ${result.error}", result.isSuccess)
        val data = result.data ?: ""
        assertTrue("应包含高德地图: $data", data.contains("高德地图 → com.autonavi.minimap"))
        assertFalse("不应包含微信: $data", data.contains("微信 →"))
        assertFalse("不应包含支付宝: $data", data.contains("支付宝 →"))
    }

    @Test
    fun `execute maxResults限制_应不超过上限`() = runBlocking {
        // 注入 5 个 App
        injectInstalledAppsCache(
            mapOf(
                "微信" to "com.tencent.mm",
                "QQ" to "com.tencent.mobileqq",
                "抖音" to "com.ss.android.ugc.aweme",
                "淘宝" to "com.taobao.taobao",
                "京东" to "com.jingdong.app.mall"
            )
        )

        // BaseTool.optionalInt 强制 coerceAtLeast(default=50)，传 0 会被拉成 50 验证限数生效
        val result = listAppsTool.execute(mapOf("max_results" to 0))

        assertTrue("execute 应成功: ${result.error}", result.isSuccess)
        val data = result.data ?: ""
        assertTrue("应注明共 5 个: $data", data.contains("共 5 个"))
        // 验证只输出 50 行内的全部（5 个全输出）
        val lines = data.lines().filter { it.startsWith("- ") }
        assertEquals("应只输出 5 行（强制下限=50但实际只有5个）", 5, lines.size)
    }

    @Test
    fun `execute 缓存为空_应返回错误`() = runBlocking {
        // 故意不注入缓存
        clearInstalledAppsCache()

        val result = listAppsTool.execute(emptyMap())

        assertFalse("execute 应失败（缓存未就绪）", result.isSuccess)
        val error = result.error ?: ""
        assertTrue("错误应提示缓存未就绪: $error",
            error.contains("缓存未就绪") || error.contains("未安装"))
    }

    @Test
    fun `getName 应为list_apps`() {
        assertEquals("list_apps", listAppsTool.getName())
    }

    @Test
    fun `parameters 应包含 keywords 和 max_results`() {
        val params = listAppsTool.getParameters()
        val names = params.map { it.name }
        assertTrue("应包含 keywords 参数", "keywords" in names)
        assertTrue("应包含 max_results 参数", "max_results" in names)
    }

    @Test
    fun `execute 多关键词过滤_应同时返回多个匹配项`() = runBlocking {
        injectInstalledAppsCache(
            mapOf(
                "微信" to "com.tencent.mm",
                "高德地图" to "com.autonavi.minimap",
                "支付宝" to "com.eg.android.AlipayGphone"
            )
        )
        val result = listAppsTool.execute(mapOf("keywords" to listOf("地图", "支付")))
        assertTrue("execute 应成功: ${result.error}", result.isSuccess)
        val data = result.data ?: ""
        assertTrue("应包含高德地图: $data", data.contains("高德地图"))
        assertTrue("应包含支付宝: $data", data.contains("支付宝"))
        assertFalse("不应包含微信: $data", data.contains("微信"))
        assertTrue("应注明关键词: $data", data.contains("地图, 支付"))
    }

    // ===== 反射工具 =====

    private fun injectInstalledAppsCache(appMap: Map<String, String>) {
        val cache = appMap.entries.map { (name, pkg) -> name to pkg }
        InstalledAppProvider::class.java.getDeclaredField("installedAppsCache").apply {
            isAccessible = true
            set(InstalledAppProvider, cache)
        }
    }

    private fun clearInstalledAppsCache() {
        InstalledAppProvider::class.java.getDeclaredField("installedAppsCache").apply {
            isAccessible = true
            set(InstalledAppProvider, null)
        }
    }
}
