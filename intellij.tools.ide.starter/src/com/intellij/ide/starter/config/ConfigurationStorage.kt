package com.intellij.ide.starter.config

import com.intellij.ide.starter.di.di
import org.kodein.di.direct
import org.kodein.di.instance
import java.util.concurrent.ConcurrentHashMap

/**
 * Key-Value storage for parameters to tweak starter behaviour
 */
abstract class ConfigurationStorage {
  companion object {
    private val _map: ConcurrentHashMap<String, String> = ConcurrentHashMap<String, String>()

    fun instance(): ConfigurationStorage = di.direct.instance<ConfigurationStorage>()
  }

  fun put(key: String, value: String?) {
    _map[key] = value ?: ""
  }

  fun put(key: String, value: Boolean) {
    _map[key] = value.toString()
  }

  fun get(key: String): String? = _map[key]

  fun <T> get(key: String, converter: (String?) -> T): T {
    val value = get(key)
    return converter(value)
  }

  fun getBoolean(key: String): Boolean = get(key).toBoolean()

  /**
   * Reset to default values, that will be performed after each test
   */
  abstract fun resetToDefault()

  init {
    resetToDefault()
  }

  fun getAll() = _map.toMap()
}