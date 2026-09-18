package com.mcmobile.server.data

import java.io.File

/**
 * 实例目录的解析规则，纯文件运算（不依赖 Android），便于单测。
 *
 * 全项目只有 [InstanceRepository.instanceDir] 依赖这个规则，其余代码拿到的都是一个 File，
 * 所以"实例放哪儿"这件事只有这一个决定点。
 */
object InstancePaths {

    const val INTERNAL_DIR = "servers"

    fun internalRoot(filesDir: File): File = File(filesDir, INTERNAL_DIR)

    /**
     * EXTERNAL 但缺少 externalRoot（清单被手工改坏）时退回内部根目录：让调用方拿到一个
     * 不存在的路径并报错，好过在这里抛异常把实例列表和启动流程一起带崩。
     */
    fun resolve(filesDir: File, storage: StorageKind, dirName: String, externalRoot: String?): File {
        val root = if (storage == StorageKind.EXTERNAL && !externalRoot.isNullOrBlank()) {
            File(externalRoot)
        } else {
            internalRoot(filesDir)
        }
        return File(root, dirName)
    }
}
