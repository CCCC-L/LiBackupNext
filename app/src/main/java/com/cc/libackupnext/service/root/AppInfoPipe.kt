package com.cc.libackupnext.service.root

import com.cc.libackupnext.data.AppInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * 应用元数据列表的管道编码：root 侧写、主进程读。
 *
 * 头一个 int 是条数；[COUNT_ERROR] 表示服务端枚举失败，读端据此立刻报错，
 * 而不是傻等一个永远不会来的 EOF。
 */
internal object AppInfoPipe {

    const val COUNT_ERROR = -1

    fun write(out: DataOutputStream, infos: List<AppInfo>) {
        out.writeInt(infos.size)
        for (a in infos) {
            out.writeUTF(a.name)
            out.writeUTF(a.packageName)
            out.writeUTF(a.version)
            out.writeLong(a.versionCode)
            out.writeLong(a.installTime)
            out.writeLong(a.updateTime)
            out.writeInt(a.userId)
            out.writeBoolean(a.isSystem)
            out.writeBoolean(a.isFrozen)
        }
    }

    /** 条数异常或服务端枚举失败都抛 [IOException]，调用方不必再判。 */
    fun read(input: DataInputStream): List<AppInfo> {
        val count = input.readInt()
        if (count == COUNT_ERROR) throw IOException("root 服务读取应用列表失败")

        return List(count) {
            AppInfo(
                name = input.readUTF(),
                packageName = input.readUTF(),
                version = input.readUTF(),
                versionCode = input.readLong(),
                installTime = input.readLong(),
                updateTime = input.readLong(),
                userId = input.readInt(),
                isSystem = input.readBoolean(),
                isFrozen = input.readBoolean(),
            )
        }
    }
}
