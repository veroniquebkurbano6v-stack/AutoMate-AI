package com.palmagent.app.tool.impl

class ScrollDownTool : DirectionalScrollTool(ScrollDirection.DOWN) {
    override fun getName() = "scroll_down"
    override fun getDescriptionEN() =
        "Scroll down. Auto-calculates swipe coordinates based on screen size. Optional: specify start_y/start_x to start from a custom position."
    override fun getDescriptionCN() =
        "向下滑动屏幕。自动检测屏幕尺寸并计算滑动坐标。可选参数：start_y指定起始Y坐标（如传2300则从底部开始向下滑），start_x指定起始X坐标。不传参数则默认从屏幕中部滑动。用于：浏览下方内容、查看更多列表项。"
    override fun getDisplayName() = "向下滑动"
}
