package com.palmagent.app.tool.impl

class ScrollLeftTool : DirectionalScrollTool(ScrollDirection.LEFT) {
    override fun getName() = "scroll_left"
    override fun getDescriptionEN() =
        "Scroll left. Auto-calculates swipe coordinates based on screen size. Optional: specify start_x/start_y to start from a custom position."
    override fun getDescriptionCN() =
        "向左滑动屏幕。自动检测屏幕尺寸并计算滑动坐标。可选参数：start_x指定起始X坐标，start_y指定起始Y坐标。不传参数则默认从屏幕中部滑动。用于：切换右侧标签页、查看左方内容。"
    override fun getDisplayName() = "向左滑动"
}
