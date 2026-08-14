package com.palmagent.app.tool.impl

class ScrollUpTool : DirectionalScrollTool(ScrollDirection.UP) {
    override fun getName() = "scroll_up"
    override fun getDescriptionEN() =
        "Scroll up. Auto-calculates swipe coordinates based on screen size. Optional: specify start_y/start_x to start from a custom position."
    override fun getDescriptionCN() =
        "向上滑动屏幕。自动检测屏幕尺寸并计算滑动坐标。可选参数：start_y指定起始Y坐标（如传2300则从底部开始向上滑），start_x指定起始X坐标。不传参数则默认从屏幕中部滑动。用于：查看上方内容、回到页面顶部。"
    override fun getDisplayName() = "向上滑动"
}
