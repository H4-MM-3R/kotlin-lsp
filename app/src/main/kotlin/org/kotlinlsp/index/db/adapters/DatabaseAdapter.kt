package org.kotlinlsp.index.db.adapters

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

interface DatabaseAdapter {
    fun putRawData(key: String, value: ByteArray)
    fun putRawData(values: Iterable<Pair<String, ByteArray>>)
    fun getRawData(key: String): ByteArray?
    fun prefixSearch(prefix: String): Sequence<Pair<String, ByteArray>>
    fun remove(key: String)
    fun remove(keys: Iterable<String>)
    fun close()
    fun deleteDb()
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T: Any> DatabaseAdapter.put(key: String, value: T) {
    putRawData(key, ProtoBuf.encodeToByteArray(value))
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T: Any> DatabaseAdapter.put(values: Iterable<Pair<String, T>>) {
    putRawData(values.map { Pair(it.first, ProtoBuf.encodeToByteArray(it.second)) })
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> DatabaseAdapter.get(key: String): T? {
    val data = getRawData(key) ?: return null
    return ProtoBuf.decodeFromByteArray<T>(data)
}
