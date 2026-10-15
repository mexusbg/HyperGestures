package com.android.systemui.shared.system

import android.util.Log
import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sets the global (static var in Thread) uncaught exception pre-handler to an implementation that
 * delegates to each item in a list of registered UncaughtExceptionHandlers.
 */
@Singleton
class UncaughtExceptionPreHandlerManager @Inject constructor() {
    private val handlers: MutableList<UncaughtExceptionHandler> = CopyOnWriteArrayList()
    private val globalUncaughtExceptionPreHandler = GlobalUncaughtExceptionHandler()

    /**
     * Adds an exception pre-handler to the list of handlers. If this has not yet set the global
     * (static var in Thread) uncaught exception pre-handler yet, it will do so.
     */
    fun registerHandler(handler: UncaughtExceptionHandler) {
        checkGlobalHandlerSetup()
        addHandler(handler)
    }

    /**
     * Verifies that the global handler is set in Thread. If not, sets is up.
     */
    private fun checkGlobalHandlerSetup() {
        val currentHandler = getThreadPreHandler()
        if (currentHandler != globalUncaughtExceptionPreHandler) {
            if (currentHandler is GlobalUncaughtExceptionHandler) {
                throw IllegalStateException("Two UncaughtExceptionPreHandlerManagers created")
            }
            currentHandler?.let { addHandler(it) }
            setThreadPreHandler(globalUncaughtExceptionPreHandler)
        }
    }

    // Thread.get/setUncaughtExceptionPreHandler are libcore @SystemApi(MODULE_LIBRARIES) members,
    // absent from the SDK android.jar and from the device framework.jar this is compiled against,
    // so they are called reflectively; at runtime the same hidden-API check applies as to a call.
    private fun getThreadPreHandler(): UncaughtExceptionHandler? =
        Thread::class.java.getMethod("getUncaughtExceptionPreHandler").invoke(null)
            as UncaughtExceptionHandler?

    private fun setThreadPreHandler(handler: UncaughtExceptionHandler?) {
        Thread::class.java
            .getMethod("setUncaughtExceptionPreHandler", UncaughtExceptionHandler::class.java)
            .invoke(null, handler)
    }

    /**
     * Adds a handler if it has not already been added, preserving order.
     */
    private fun addHandler(it: UncaughtExceptionHandler) {
        if (it !in handlers) {
            handlers.add(it)
        }
    }

    /**
     * Calls uncaughtException on all registered handlers, catching and logging any new exceptions.
     */
    fun handleUncaughtException(thread: Thread?, throwable: Throwable?) {
        for (handler in handlers) {
            try {
                handler.uncaughtException(thread, throwable)
            } catch (e: Exception) {
                Log.wtf("Uncaught exception pre-handler error", e)
            }
        }
    }

    /**
     * UncaughtExceptionHandler impl that will be set as Thread's pre-handler static variable.
     */
    inner class GlobalUncaughtExceptionHandler : UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread?, throwable: Throwable?) {
            handleUncaughtException(thread, throwable)
        }
    }
}