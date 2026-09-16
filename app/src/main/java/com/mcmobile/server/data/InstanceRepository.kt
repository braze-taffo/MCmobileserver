package com.mcmobile.server.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 服务器实例仓库：JSON 清单存 files/instances.json，
 * 实例本体在 files/servers/<dirName>/。
 */
class InstanceRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _instances = MutableStateFlow<List<ServerInstance>>(emptyList())
    val instances: StateFlow<List<ServerInstance>> = _instances.asStateFlow()

    fun serversRoot(): File = File(context.filesDir, "servers")

    fun instanceDir(instance: ServerInstance): File = File(serversRoot(), instance.dirName)

    suspend fun load() = withContext(Dispatchers.IO) {
        val f = manifestFile()
        _instances.value = if (f.exists()) {
            runCatching {
                json.decodeFromString(ListSerializer(ServerInstance.serializer()), f.readText())
            }.getOrDefault(emptyList())
        } else emptyList()
    }

    private fun manifestFile(): File = File(context.filesDir, "instances.json")

    private suspend fun persist() = withContext(Dispatchers.IO) {
        manifestFile().writeText(json.encodeToString(ListSerializer(ServerInstance.serializer()), _instances.value))
    }

    suspend fun create(
        name: String,
        type: ServerType,
        mcVersion: String,
        coreVersion: String?,
        maxHeapMb: Int,
        javaMajor: Int,
    ): ServerInstance = mutex.withLock {
        val uid = UUID.randomUUID().toString()
        val instance = ServerInstance(
            id = uid,
            name = name.ifBlank { "$type-$mcVersion" },
            type = type,
            mcVersion = mcVersion,
            coreVersion = coreVersion,
            dirName = sanitize("$type-$mcVersion-${uid.take(4)}").lowercase(),
            maxHeapMb = maxHeapMb,
            javaMajor = javaMajor,
            createdMs = System.currentTimeMillis(),
            installed = type != ServerType.FORGE && type != ServerType.NEOFORGE,
        )
        val dir = File(serversRoot(), instance.dirName)
        dir.mkdirs()
        File(dir, "mods").mkdirs()
        File(dir, "config").mkdirs()
        _instances.value = _instances.value + instance
        persist()
        instance
    }

    suspend fun update(instance: ServerInstance) = mutex.withLock {
        _instances.value = _instances.value.map { if (it.id == instance.id) instance else it }
        persist()
    }

    suspend fun delete(instance: ServerInstance) = mutex.withLock {
        File(serversRoot(), instance.dirName).deleteRecursively()
        _instances.value = _instances.value.filterNot { it.id == instance.id }
        persist()
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
