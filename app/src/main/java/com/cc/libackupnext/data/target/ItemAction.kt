package com.cc.libackupnext.data.target

/**
 * 一个备份目标支持的操作。
 *
 * 放在 data 层而不是 UI：它描述的是「这种目标能做什么」——
 * 是领域语义，不是渲染细节。UI 只负责把它翻译成菜单项文案和颜色
 * （见 `ui/dialog/AppOptionsDialog.kt` 里的 `title()` / `isDanger`）。
 *
 * 加新类型时：这里加需要的动作（或复用已有的），再在 `TargetSpecs` 里声明。
 */
enum class ItemAction {
    /** 应用 */
    LAUNCH,
    FORCE_STOP,
    FREEZE,
    UNFREEZE,
    APP_SETTINGS,
    CLEAR_DATA,
    UNINSTALL,

    /** 目录 */
    OPEN_DIR,
    DELETE_DIR,

    /** Wi-Fi */
    WIFI_ON,
    WIFI_OFF,
    OPEN_WIFI_SETTINGS,

    /** 联系人 */
    OPEN_CONTACTS,

    /** 通话记录 */
    OPEN_CALL_LOG,

    /** 短彩信 */
    OPEN_SMS
}
