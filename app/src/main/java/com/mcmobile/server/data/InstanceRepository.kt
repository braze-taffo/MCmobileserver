package com.mcmobile.server.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 服务器实例仓库：JSON 清单存 files/instances.json，
 * 实例本体在内部 files/servers/<dirName>/，或用户选择的外部目录 <externalRoot>/<dirName>/。
 */
class InstanceRepository(private val context: Context) {

    private val mutex = Mutex()

    private val _instances = MutableStateFlow<List<ServerInstance>>(emptyList())
    val instances: StateFlow<List<ServerInstance>> = _instances.asStateFlow()

    /** 加载期问题（清单损坏等），供 UI 提示；正常为 null */
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    fun serversRoot(): File = InstancePaths.internalRoot(context.filesDir)

    fun instanceDir(instance: ServerInstance): File = InstancePaths.resolve(
        filesDir = context.filesDir,
        storage = instance.storage,
        dirName = instance.dirName,
        externalRoot = instance.externalRoot,
    )

    suspend fun load() = withContext(Dispatchers.IO) {
        val f = manifestFile()
        if (!f.exists()) {
            _instances.value = emptyList()
            _loadError.value = null
            return@withContext
        }
        val text = runCatching { f.readText() }.getOrNull()
        if (text == null) {
            _instances.value = emptyList()
            _loadError.value = "无法读取实例清单 ${f.name}，实例列表暂不可用"
            return@withContext
        }
        val parsed = InstanceManifest.decode(text)
        if (parsed != null) {
            _instances.value = parsed
            _loadError.value = null
            return@withContext
        }
        // 清单是存档目录的唯一索引：损坏时只备份、不静默清空后覆盖
        val backup = InstanceManifest.backupCorrupt(f)
        _instances.value = emptyList()
        _loadError.value = if (backup != null) {
            "实例清单已损坏，原文件已备份为 ${backup.name}"
        } else {
            "实例清单已损坏且备份失败，请勿在此设备继续新建实例"
        }
    }

    fun dismissLoadError() {
        _loadError.value = null
    }

    private fun manifestFile(): File = File(context.filesDir, "instances.json")

    private suspend fun persist() = withContext(Dispatchers.IO) {
        InstanceManifest.writeAtomically(manifestFile(), InstanceManifest.encode(_instances.value))
    }

    suspend fun create(
        name: String,
        type: ServerType,
        mcVersion: String,
        coreVersion: String?,
        maxHeapMb: Int,
        javaMajor: Int,
        storage: StorageKind = StorageKind.INTERNAL,
        /** EXTERNAL 时为用户所选目录的绝对路径 */
        externalRoot: String? = null,
    ): ServerInstance = mutex.withLock {
        val uid = UUID.randomUUID().toString()
        val instance = ServerInstance(
            id = uid,
            name = name.ifBlank { "$type-$mcVersion" },
            type = type,
            mcVersion = mcVersion,
            coreVersion = coreVersion,
            dirName = sanitize("$type-$mcVersion-${uid.take(4)}").lowercase(),
            storage = storage,
            externalRoot = externalRoot?.takeIf { storage == StorageKind.EXTERNAL },
            maxHeapMb = maxHeapMb,
            javaMajor = javaMajor,
            createdMs = System.currentTimeMillis(),
            installed = type != ServerType.FORGE && type != ServerType.NEOFORGE,
        )
        val dir = instanceDir(instance)
        // 外部目录可能因权限/拔卡/只读卷建不出来；这种失败必须当场抛出，
        // 否则清单里会留下一个指向空目录的实例，用户点启动才知道出问题
        check(dir.isDirectory || dir.mkdirs()) { "无法创建实例目录：${dir.absolutePath}" }
        File(dir, "mods").mkdirs()
        File(dir, "config").mkdirs()
        InstanceMarkers.write(dir, instance)
        _instances.value = _instances.value + instance
        persist()
        instance
    }

    suspend fun update(instance: ServerInstance) = mutex.withLock {
        _instances.value = _instances.value.map { if (it.id == instance.id) instance else it }
        persist()
    }

    /**
     * 删除实例：目录 + 清单项。
     *
     * @param deleteFiles false 表示只把实例移出列表、磁盘目录原样保留（外部目录里的实例，
     *   用户可能想自己留着文件）。
     * @return 实例目录是否已确认删除干净（目录不存在、或本来就要求保留时也算）。清单项无论如何
     *   都会移除，否则操作失败的实例会永远留在列表里删不掉。
     */
    suspend fun delete(instance: ServerInstance, deleteFiles: Boolean = true): Boolean = mutex.withLock {
        val dir = instanceDir(instance)
        val removed = !deleteFiles || !dir.exists() || dir.deleteRecursively()
        _instances.value = _instances.value.filterNot { it.id == instance.id }
        persist()
        removed
    }

    fun byId(id: String): ServerInstance? = _instances.value.firstOrNull { it.id == id }

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "-")

    companion object {
        @Volatile
        private var instance: InstanceRepository? = null

        fun get(context: Context): InstanceRepository =
            instance ?: synchronized(this) {
                instance ?: InstanceRepository(context.applicationContext).also { instance = it }
            }
    }
}
