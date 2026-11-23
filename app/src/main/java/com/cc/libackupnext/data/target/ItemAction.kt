package com.cc.libackupnext.data.target

/**
 * 一个备份目标支持的操作。
 *
 * 放在 data 层而不是 UI：它描述的是「这种目标能做什么」——
 * 是领域语义，不是渲染细节。
 *
 * 加新类型时：这里加需要的动作（或复用已有的），再在 `TargetSpecs` 里声明。
 *
 * @param title 菜单项文案
 * @param destructive 点下去不可逆（清数据 / 卸载 / 删目录）：菜单标红，执行前先让用户确认。
 *   判据跟着动作走，因为「要不要拦一下」是动作自身的性质，不是某个页面的口味；
 *   拆到 UI 层两份清单（一份标红、一份弹确认）迟早会漂移。
 */
enum class ItemAction(val title: String, val destructive: Boolean = false) {
    /** 应用 */
    LAUNCH("启动应用"),
    FORCE_STOP("强制停止"),
    FREEZE("冻结应用"),
    UNFREEZE("解冻应用"),
    APP_SETTINGS("系统设置"),
    CLEAR_DATA("清空数据", destructive = true),
    UNINSTALL("卸载应用", destructive = true),

    /** 目录 */
    OPEN_DIR("打开文件夹"),
    DELETE_DIR("删除文件夹", destructive = true),

    /** Wi-Fi */
    WIFI_ON("打开 Wi-Fi"),
    WIFI_OFF("关闭 Wi-Fi"),
    OPEN_WIFI_SETTINGS("Wi-Fi 设置"),

    /** 联系人 */
    OPEN_CONTACTS("打开联系人"),

    /** 通话记录 */
    OPEN_CALL_LOG("打开通话记录"),

    /** 短彩信 */
    OPEN_MESSAGING("打开短彩信")
}
