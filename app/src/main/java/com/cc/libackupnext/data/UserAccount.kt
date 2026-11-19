package com.cc.libackupnext.data

/**
 * 设备上的一个用户（主用户 / 分身 / 工作资料）。
 *
 * 不用 `android.content.pm.UserInfo`：那是 Android 框架类型，放进
 * `DeviceGateway` 这类接口会让纯 JVM 单测无法实现它（测试 classpath 里没有这个类）。
 * 这里只留界面真正需要的两个字段。
 */
data class UserAccount(
    val id: Int,
    /** 展示名；系统没给名字时由实现方兜底成「用户 N」。 */
    val name: String,
)
