package com.swiftbrowser.core

import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Threads for work nobody waits on frame by frame (2.6): saving, cleaning up, fetching news. They
 * run below the screen and the page drawing, so scrolling stays smooth while they work.
 */
object Background {
    fun single(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, name)
    }

    fun pool(name: String, threads: Int): ExecutorService = Executors.newFixedThreadPool(threads) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, name)
    }
}
