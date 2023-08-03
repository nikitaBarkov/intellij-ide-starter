package com.intellij.ide.starter.bus

import com.intellij.ide.starter.utils.catchAll
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull

/**
 * @author https://github.com/Kosert/FlowBus
 * @license Apache 2.0 https://github.com/Kosert/FlowBus/blob/master/LICENSE
 * Class for receiving events posted to [FlowBus]
 *
 * @param bus [FlowBus] instance to subscribe to. If not set, [StarterBus] will be used
 */
open class EventsReceiver @JvmOverloads constructor(private val bus: FlowBus = StarterBus) {
  private val jobs = mutableMapOf<Class<*>, List<Job>>()

  private var returnDispatcher: CoroutineDispatcher = Dispatchers.IO

  /**
   * Subscribe to events that are type of [clazz] with the given [callback] function.
   * The [callback] can be called immediately if event of type [clazz] is present in the flow.
   *
   * @param clazz Type of event to subscribe to
   * @param skipRetained Skips event already present in the flow. This is `false` by default
   * @param callback The callback function
   * @return This instance of [EventsReceiver] for chaining
   */
  @JvmOverloads
  fun <T : Any> subscribeTo(clazz: Class<T>, skipRetained: Boolean = false, callback: suspend (event: T) -> Unit): EventsReceiver {
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
      throw throwable
    }

    val job = CoroutineScope(Job() + Dispatchers.IO + exceptionHandler).launch {
      bus.forEvent(clazz)
        .drop(if (skipRetained) 1 else 0)
        .filterNotNull()
        .collect {
          catchAll {
            withContext(returnDispatcher) { callback(it) }
          }
        }
    }

    jobs.putIfAbsent(clazz, listOf(job))?.let { jobs[clazz] = it + job }
    return this
  }

  /**
   * A variant of [subscribeTo] that uses an instance of [EventCallback] as callback.
   *
   * @param clazz Type of event to subscribe to
   * @param skipRetained Skips event already present in the flow. This is `false` by default
   * @param callback Interface with implemented callback function
   * @return This instance of [EventsReceiver] for chaining
   * @see [subscribeTo]
   */
  @JvmOverloads
  fun <T : Any> subscribeTo(clazz: Class<T>, callback: EventCallback<T>, skipRetained: Boolean = false): EventsReceiver {
    return subscribeTo(clazz, skipRetained) { callback.onEvent(it) }
  }

  /**
   * Unsubscribe from all events
   */
  fun unsubscribe() {
    runBlocking {
      for (jobList in jobs.values) {
        for (job in jobList) {
          job.cancelAndJoin()
        }
      }
    }

    jobs.clear()
  }
}
