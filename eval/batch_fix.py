#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批量修复脚本：按模板库重写候选 SOP，符合 docs/kb-data-quality-prompt.md 规范。

规范要点：
  1. 能搜索一定搜索：联系人/群组/店铺/地点/歌曲 必须走"搜索框→输入→点结果"链路
  2. UI 描述具体：位置（右上角/顶部/底部/面板中）+ 控件名（引号文字）
  3. 无占位词（指定×××/特定×××/对应×××）
  4. "点击搜索框+输入"合并为一步（action_type=type）
  5. 微信发送/分享：分享面板→微信→搜索联系人→点结果→确认对话框→确认
  6. 进入App后先导航到目标界面（如网易云点'正在播放'图标进播放页）
  7. 字段完整：sop_id/original_task_name/task_name/app_name/source/difficulty/domain/keywords/steps
  8. action_type 仅 click/type/long_press/swipe

用法：
    python eval/batch_fix.py --kb-dir app/src/main/assets/kb/sop_raw
    （--dry-run 只输出不改写）
"""

import argparse
import json
import os
import re
from typing import Dict, List

# ============ 模板库：每个模板按 task 特征返回 steps ============

T_WEIXIN_ZHANFA = [
    ("点击手机桌面上的微信应用图标", "进入微信主界面，显示聊天列表", "click"),
    ("点击微信主界面顶部的搜索框输入'联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的联系人条目", "进入与该联系人的聊天界面", "click"),
    ("长按要转发的消息气泡", "弹出操作菜单，包含转发、收藏、删除等选项", "long_press"),
    ("点击操作菜单中的'转发'选项", "进入选择聊天界面，显示最近聊天列表", "click"),
    ("点击选择聊天界面上方的搜索框输入'群组或联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的目标条目", "弹出包含'确认'和'取消'按钮的确认转发对话框", "click"),
    ("点击对话框中的'确认'按钮", "消息已转发成功，任务完成", "click"),
]

T_WEIXIN_FAXIAOXI = [
    ("点击手机桌面上的微信应用图标", "进入微信主界面，显示聊天列表", "click"),
    ("点击微信主界面顶部的搜索框输入'群组或联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的目标条目", "进入与目标对象的聊天界面", "click"),
    ("点击聊天界面底部的消息输入框输入消息内容", "消息输入框中显示要发送的文本", "type"),
    ("点击输入框右侧的'发送'按钮", "消息已发送给目标对象，任务完成", "click"),
]

T_WEIXIN_WEIZHI = [
    ("点击手机桌面上的微信应用图标", "进入微信主界面，显示聊天列表", "click"),
    ("点击微信主界面顶部的搜索框输入'联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的联系人条目", "进入与该联系人的聊天界面", "click"),
    ("点击聊天界面右下角的'+'按钮", "弹出功能面板，显示位置、图片、视频、文件等选项", "click"),
    ("点击功能面板中的'位置'选项", "进入位置发送界面，显示当前位置地图", "click"),
    ("点击界面底部的'发送位置'按钮", "当前位置已发送给联系人，任务完成", "click"),
]

T_WEIXIN_TONGHUA = [
    ("点击手机桌面上的微信应用图标", "进入微信主界面，显示聊天列表", "click"),
    ("点击微信主界面顶部的搜索框输入'联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的联系人条目", "进入与该联系人的聊天界面", "click"),
    ("点击聊天界面右上角的'语音通话'或'视频通话'按钮", "弹出通话确认界面", "click"),
    ("点击确认弹窗中的'呼叫'按钮", "开始呼叫联系人，任务完成", "click"),
]

T_WEIXIN_BOFANGYUYIN = [
    ("点击手机桌面上的微信应用图标", "进入微信主界面，显示聊天列表", "click"),
    ("点击微信主界面顶部的搜索框输入'群组或联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的目标条目", "进入与目标对象的聊天界面", "click"),
    ("点击最新一条语音消息的气泡", "语音消息开始播放，任务完成", "click"),
]

T_WEIXIN_DIZHI_DAOHANG = [
    ("点击手机桌面上的微信应用图标", "进入微信主界面，显示聊天列表", "click"),
    ("点击微信主界面顶部的搜索框输入'联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的联系人条目", "进入与该联系人的聊天界面", "click"),
    ("点击联系人发送的地址消息卡片", "弹出地址详情界面，显示地图和位置信息", "click"),
    ("点击界面底部的'导航'按钮", "进入地图应用选择界面", "click"),
    ("点击地图应用列表中的'高德地图'", "进入高德地图路线规划界面，任务完成", "click"),
]

T_WEIXIN_PENGYOUQUAN = [
    ("点击手机桌面上的微信应用图标", "进入微信主界面，显示聊天列表", "click"),
    ("点击微信主界面顶部的搜索框输入'联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的联系人条目", "进入与该联系人的聊天界面", "click"),
    ("点击聊天界面右上角的'...'更多按钮", "弹出功能菜单，包含查看朋友圈等选项", "click"),
    ("点击菜单中的'个人名片'选项进入联系人主页", "进入联系人个人主页，显示朋友圈入口", "click"),
    ("点击联系人主页中的'朋友圈'入口", "进入该联系人的朋友圈列表", "click"),
    ("点击目标朋友圈动态下方的'点赞'图标", "点赞成功，任务完成", "click"),
]

T_WEIXIN_RENYUAN = [
    ("点击手机桌面上的微信应用图标", "进入微信主界面，显示聊天列表", "click"),
    ("点击微信主界面顶部的搜索框输入'联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的联系人条目", "进入与该联系人的聊天界面", "click"),
    ("点击联系人发送的定位消息卡片", "弹出位置详情界面，显示地图和位置信息", "click"),
    ("点击界面底部的'导航'按钮", "进入地图应用选择界面", "click"),
    ("点击地图应用列表中的'高德地图'", "进入高德地图路线规划界面，任务完成", "click"),
]

T_GAODE_FENXIANG = [
    ("点击手机桌面上的高德地图应用图标", "进入高德地图主界面", "click"),
    ("点击页面顶部的搜索框输入'目的地名称'", "界面显示搜索结果列表", "type"),
    ("点击搜索结果列表上方的'推荐排序'按钮", "弹出排序方式菜单，包含推荐排序、距离优先、好评优先等选项", "click"),
    ("选择排序方式为'距离优先'", "列表按距离从近到远重新排列", "click"),
    ("点击距离最近的目标店铺条目", "进入该店铺的详细信息页面，显示地址、评分和营业信息", "click"),
    ("点击详情页底部的'分享'按钮", "弹出分享面板，显示微信、朋友圈等分享方式选项", "click"),
    ("点击分享面板中的'微信'图标", "进入微信分享界面，显示最近聊天列表", "click"),
    ("点击微信分享界面上方的搜索框输入'联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的联系人条目", "弹出包含'确认'和'取消'按钮的确认分享对话框", "click"),
    ("点击对话框中的'确认'按钮", "店铺位置已分享给联系人，任务完成", "click"),
]

T_GAODE_DAOHANG = [
    ("点击手机桌面上的高德地图应用图标", "进入高德地图主界面", "click"),
    ("点击页面顶部的搜索框输入'目的地名称'", "界面显示搜索结果列表", "type"),
    ("点击搜索结果中的目的地条目", "进入该地点的详细信息页面", "click"),
    ("点击详情页底部的'导航'按钮", "进入路线规划界面，显示推荐路线", "click"),
    ("点击'开始导航'按钮", "进入实时导航模式，任务完成", "click"),
]

T_MEITUAN_PAIDUI = [
    ("点击手机桌面上的美团应用图标", "进入美团主界面", "click"),
    ("点击页面顶部的搜索框输入'菜系或餐馆名称'", "界面显示搜索结果列表", "type"),
    ("点击搜索结果列表上方的'排序'按钮", "弹出排序方式菜单", "click"),
    ("选择排序方式为'好评优先'", "餐馆列表按用户评价从高到低排列", "click"),
    ("点击目标餐馆条目", "进入该餐馆的详细信息页面", "click"),
    ("点击详情页中的'排队取号'按钮", "进入排队取号界面", "click"),
    ("点击'确认取号'按钮", "取号成功，任务完成", "click"),
]

T_MEITUAN_WAIMAI = [
    ("点击手机桌面上的美团应用图标", "进入美团主界面", "click"),
    ("点击页面顶部的搜索框输入'店铺或菜品名称'", "界面显示搜索结果列表", "type"),
    ("点击目标店铺条目", "进入该店铺的详细界面", "click"),
    ("点击目标菜品右侧的'选规格'按钮", "弹出规格选择表单", "click"),
    ("根据需求选择规格并点击'选好了'按钮", "商品加入购物车", "click"),
    ("点击界面底部的'结算'按钮", "进入订单确认界面", "click"),
    ("点击'提交订单'按钮", "订单提交成功，任务完成", "click"),
]

T_YINYUE_FENXIANG = [
    ("点击手机桌面上的音乐应用图标", "进入音乐应用主界面", "click"),
    ("点击界面底部、下方导航栏上方一点的'正在播放'音乐图标", "进入当前播放歌曲的播放界面", "click"),
    ("点击播放界面右上角的分享按钮", "弹出分享面板，显示微信、朋友圈等分享选项", "click"),
    ("点击分享面板中的'微信'选项", "跳转到微信分享界面，显示最近聊天列表", "click"),
    ("点击微信分享界面上方的搜索框输入'联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的联系人条目", "弹出包含'确认'和'取消'按钮的确认分享对话框", "click"),
    ("点击对话框中的'确认'按钮", "歌曲分享卡片已发送给联系人，任务完成", "click"),
]

T_YINYUE_QIHUAN = [
    ("点击手机桌面上的音乐应用图标", "进入音乐应用主界面", "click"),
    ("点击界面底部、下方导航栏上方一点的'正在播放'音乐图标", "进入当前播放歌曲的播放界面", "click"),
    ("点击播放界面的'暂停'按钮停止当前歌曲", "当前歌曲停止播放", "click"),
    ("点击界面底部的'我的'按钮进入个人中心", "进入个人中心页面", "click"),
    ("点击'收藏'入口进入收藏歌曲列表", "显示已收藏的歌曲列表", "click"),
    ("点击收藏列表中的目标歌曲", "该歌曲开始播放，任务完成", "click"),
]

T_YINYUE_GEDAN = [
    ("点击手机桌面上的音乐应用图标", "进入音乐应用主界面", "click"),
    ("点击页面顶部的搜索框输入'歌单名称'", "界面显示搜索结果列表", "type"),
    ("点击搜索结果中的目标歌单条目", "进入歌单详情页面，显示歌曲列表", "click"),
    ("点击歌单中的目标歌曲", "该歌曲开始播放，任务完成", "click"),
]

T_YINYUE_DI_N = [
    ("点击手机桌面上的音乐应用图标", "进入音乐应用主界面", "click"),
    ("点击页面顶部的搜索框输入'歌曲名称'", "界面显示搜索结果列表", "type"),
    ("点击搜索结果中的目标歌曲条目", "进入歌曲详情或播放界面", "click"),
    ("点击歌曲列表中的第N首歌曲", "从第N首歌曲开始播放，任务完成", "click"),
]

T_TENCENT_MEET_JOIN = [
    ("点击手机桌面上的腾讯会议应用图标", "进入腾讯会议主界面", "click"),
    ("点击'加入会议'按钮", "进入加入会议界面，显示会议号输入框", "click"),
    ("点击会议号输入框输入会议号", "会议号输入框中显示对应数字", "type"),
    ("点击'加入会议'确认按钮", "进入会议房间，任务完成", "click"),
]

T_TENCENT_MEET_QUICK = [
    ("点击手机桌面上的腾讯会议应用图标", "进入腾讯会议主界面", "click"),
    ("点击'快速会议'按钮", "弹出快速会议确认弹窗", "click"),
    ("点击弹窗中的'进入会议'按钮", "快速会议已开启并进入，任务完成", "click"),
]

T_TENCENT_MEET_MUTE = [
    ("点击手机桌面上的腾讯会议应用图标", "进入腾讯会议主界面", "click"),
    ("点击'加入会议'按钮并输入会议号进入会议", "进入会议房间", "click"),
    ("点击会议界面底部的'成员'按钮", "展开成员列表面板", "click"),
    ("点击成员列表下方的'全员静音'按钮", "全体成员被静音，任务完成", "click"),
]

T_TENCENT_MEET_SHARE = [
    ("点击手机桌面上的腾讯会议应用图标", "进入腾讯会议主界面", "click"),
    ("点击'加入会议'按钮并输入会议号进入会议", "进入会议房间", "click"),
    ("点击会议界面底部的'共享屏幕'按钮", "弹出共享选择面板", "click"),
    ("点击共享面板中的'屏幕'选项", "屏幕开始共享，任务完成", "click"),
]

T_YOUKU_HISTORY = [
    ("点击手机桌面上的优酷视频应用图标", "进入优酷视频主界面", "click"),
    ("点击界面底部的'我的'按钮进入个人中心", "进入个人中心页面", "click"),
    ("点击'观看历史'入口", "显示观看历史列表", "click"),
    ("点击历史列表中的目标视频条目", "该视频开始播放，任务完成", "click"),
]

T_DIANPING_BANGDAN = [
    ("点击手机桌面上的大众点评应用图标", "进入大众点评主界面", "click"),
    ("点击'美食排行'入口进入榜单页面", "进入美食排行榜单页面", "click"),
    ("点击'必吃榜'入口", "进入必吃榜页面，展示榜单列表", "click"),
    ("点击目标榜单分类筛选", "筛选出对应类别的餐厅", "click"),
    ("点击榜单中的第N名商户条目", "进入该商户的详细信息页面", "click"),
    ("点击详情页中的'导航'按钮", "进入地图应用选择界面", "click"),
    ("点击'高德地图'选项", "进入高德地图路线规划界面，任务完成", "click"),
]

T_BILI_UPZHU = [
    ("点击手机桌面上的哔哩哔哩应用图标", "进入哔哩哔哩主界面", "click"),
    ("点击页面顶部的搜索框输入'UP主名称'", "界面显示搜索结果列表", "type"),
    ("点击搜索结果中的UP主条目", "进入该UP主的个人空间主页", "click"),
    ("点击UP主主页中的最新视频", "该视频开始播放，任务完成", "click"),
]

T_BILI_FENXIANG = [
    ("点击手机桌面上的哔哩哔哩应用图标", "进入哔哩哔哩主界面", "click"),
    ("点击页面顶部的搜索框输入'UP主名称'", "界面显示搜索结果列表", "type"),
    ("点击搜索结果中的UP主条目", "进入该UP主的个人空间主页", "click"),
    ("点击UP主主页中的最新视频", "该视频开始播放", "click"),
    ("点击播放界面右下角的'分享'按钮", "弹出分享面板", "click"),
    ("点击分享面板中的'微信'选项", "进入微信分享界面", "click"),
    ("点击微信分享界面上方的搜索框输入'联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的联系人条目", "弹出包含'确认'和'取消'按钮的确认分享对话框", "click"),
    ("点击对话框中的'确认'按钮", "视频已分享给联系人，任务完成", "click"),
]

T_BILI_DINGYUE = [
    ("点击手机桌面上的哔哩哔哩应用图标", "进入哔哩哔哩主界面", "click"),
    ("点击页面顶部的搜索框输入'UP主名称'", "界面显示搜索结果列表", "type"),
    ("点击搜索结果中的UP主条目", "进入该UP主的个人空间主页", "click"),
    ("点击UP主主页中的'订阅'按钮", "订阅成功，任务完成", "click"),
]

T_XIEHENG_FENXIANG = [
    ("点击手机桌面上的携程旅行应用图标", "进入携程旅行主界面", "click"),
    ("点击页面顶部的搜索框输入'地点和酒店关键词'", "界面显示搜索结果列表", "type"),
    ("点击搜索结果列表上方的'筛选'按钮设置星级条件", "酒店列表按筛选条件更新", "click"),
    ("点击搜索结果中的第一家酒店条目", "进入该酒店的详细信息页面", "click"),
    ("点击详情页中的'分享'按钮", "弹出分享面板", "click"),
    ("点击分享面板中的'微信'图标", "进入微信分享界面", "click"),
    ("点击微信分享界面上方的搜索框输入'联系人姓名'", "界面显示匹配的搜索结果条目", "type"),
    ("点击搜索结果中的联系人条目", "弹出包含'确认'和'取消'按钮的确认分享对话框", "click"),
    ("点击对话框中的'确认'按钮", "酒店信息已分享给联系人，任务完成", "click"),
]

T_XIAOHONGSHU_DIANZAN = [
    ("点击手机桌面上的小红书应用图标", "进入小红书主界面", "click"),
    ("点击页面顶部的搜索框输入'笔记关键词'", "界面显示搜索结果列表", "type"),
    ("点击搜索结果中的目标笔记条目", "进入笔记详情页面", "click"),
    ("点击笔记详情页底部的'点赞'图标", "点赞成功，任务完成", "click"),
]

T_GAODE_XUEXIAO = [
    ("点击手机桌面上的高德地图应用图标", "进入高德地图主界面", "click"),
    ("点击页面顶部的搜索框输入'学校名称'", "界面显示搜索结果列表", "type"),
    ("点击搜索结果中的目标学校条目", "进入该学校的详细信息页面，显示地址和评分", "click"),
    ("点击详情页底部的'导航'按钮", "进入路线规划界面，任务完成", "click"),
]


def build_steps(tpl: List[tuple]) -> List[Dict]:
    steps = []
    for i, (goal, expected, act) in enumerate(tpl, start=1):
        steps.append({"step_order": i, "goal": goal, "expected": expected, "action_type": act})
    return steps


# 模板路由：按 task/app 关键词匹配
def match_template(task: str, app: str):
    t = task
    if app == "腾讯会议":
        if "静音" in t: return T_TENCENT_MEET_MUTE
        if "屏幕共享" in t: return T_TENCENT_MEET_SHARE
        if "快速会议" in t or "会议号" in t: return T_TENCENT_MEET_QUICK
        return T_TENCENT_MEET_JOIN
    # 注意：微信分支必须限定 app=="微信"。
    # 跨 App 分享任务（如"QQ音乐分享歌曲给微信联系人"）的 app 是音乐/高德/B站等，
    # 不得因 task 含"微信"而误入本分支。
    if app == "微信":
        if "转发" in t: return T_WEIXIN_ZHANFA
        if "位置" in t and ("发送" in t or "共享" in t or "分享" in t): return T_WEIXIN_WEIZHI
        if "语音电话" in t or "视频电话" in t: return T_WEIXIN_TONGHUA
        if "语音" in t: return T_WEIXIN_BOFANGYUYIN
        if "地址" in t and "导航" in t: return T_WEIXIN_DIZHI_DAOHANG
        if "朋友圈" in t: return T_WEIXIN_PENGYOUQUAN
        if "导航" in t and "位置" in t: return T_WEIXIN_RENYUAN
        if "导航" in t: return T_WEIXIN_DIZHI_DAOHANG
        if "消息" in t and ("发" in t or "发条" in t): return T_WEIXIN_FAXIAOXI
        return T_WEIXIN_FAXIAOXI
    if app in ("QQ音乐", "酷狗音乐", "网易云音乐"):
        if "分享" in t: return T_YINYUE_FENXIANG
        if "停止播放" in t: return T_YINYUE_QIHUAN
        if "歌单" in t: return T_YINYUE_GEDAN
        if "第" in t: return T_YINYUE_DI_N
        return T_YINYUE_FENXIANG
    if app == "高德地图":
        if "分享" in t: return T_GAODE_FENXIANG
        if "学校" in t: return T_GAODE_XUEXIAO
        return T_GAODE_DAOHANG
    if app == "美团":
        if "送到家" in t or "外卖" in t: return T_MEITUAN_WAIMAI
        return T_MEITUAN_PAIDUI
    if app == "大众点评":
        if "榜单" in t: return T_DIANPING_BANGDAN
        return T_DIANPING_BANGDAN
    if app == "B站" or "哔哩哔哩" in t:
        if "分享" in t: return T_BILI_FENXIANG
        if "订阅" in t: return T_BILI_DINGYUE
        return T_BILI_UPZHU
    if app == "携程": return T_XIEHENG_FENXIANG
    if app == "优酷视频": return T_YOUKU_HISTORY
    if app == "小红书":
        if "点赞" in t: return T_XIAOHONGSHU_DIANZAN
        return T_XIAOHONGSHU_DIANZAN
    return None


DIFFICULTY = {"购物": "L2", "生活": "L3", "社交": "L3", "媒体": "L3", "音乐": "L3",
              "导航": "L4", "效率": "L3", "查询": "L3", "生活服务": "L4"}


def domain_of(app: str) -> str:
    m = {"微信": "社交", "QQ音乐": "媒体", "酷狗音乐": "媒体", "网易云音乐": "媒体",
         "高德地图": "导航", "百度地图": "导航", "美团": "生活服务", "大众点评": "生活服务",
         "B站": "视频", "腾讯视频": "视频", "优酷视频": "视频", "携程": "生活服务",
         "腾讯会议": "效率", "小红书": "社交", "淘宝": "购物", "京东": "购物"}
    return m.get(app, "生活")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kb-dir", default="app/src/main/assets/kb/sop_raw")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    # 只处理候选（用 audit 逻辑）
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from audit_dedup import load_sops, analyze, is_candidate

    sops = load_sops(args.kb_dir)
    rows = [analyze(s) for s in sops]
    cands = [r for r in rows if is_candidate(r)]
    print(f"候选 {len(cands)} 条，开始批量重写...")

    fixed, no_tpl = 0, []
    for c in cands:
        sop = None
        for s in sops:
            if s["_file"] == c["file"]:
                sop = s
                break
        tpl = match_template(c["task_name"], c["app_name"])
        if tpl is None:
            no_tpl.append(c["file"])
            continue
        new_steps = build_steps(tpl)
        sop["steps"] = new_steps
        sop["source"] = "cagui_auto"
        # 清洗占位词：task_name 里的 特定/指定/对应 前缀去掉
        sop["task_name"] = re.sub(r"[特指对]定", "", sop["task_name"])
        if args.dry_run:
            print(f"  [dry] {c['file'][:20]} {sop['task_name'][:40]} -> {len(new_steps)}步")
        else:
            with open(os.path.join(args.kb_dir, c["file"]), "w", encoding="utf-8") as f:
                json.dump(sop, f, ensure_ascii=False, indent=2)
        fixed += 1

    print(f"\n重写完成: {fixed} 条 | 无模板: {len(no_tpl)} 条")
    for f in no_tpl:
        print(f"  无模板: {f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
