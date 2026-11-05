package com.swiftbrowser.files

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A problem worth telling the user just as it is (2.7). */
class FileProblem(message: String) : Exception(message)

/** The user cancelled the job. */
class Cancelled : Exception()

/**
 * Copying, moving, deleting, extracting and compressing (2.7): one job at a time on a background
 * thread, with its progress on the Files screen and in the notification shade (FileJobService keeps
 * Swift running meanwhile, so a job goes on when the screen is left). When a name is taken or an
 * archive needs its password, the job waits for the user's answer.
 */
object FileJobs {
    enum class Kind(val doing: String, val done: String) {
        COPY("Copying", "Copied"),
        MOVE("Moving", "Moved"),
        DELETE("Deleting", "Deleted"),
        EXTRACT("Extracting", "Extracted"),
        COMPRESS("Compressing", "Compressed"),
        OPEN("Opening", "Opened"),
    }

    enum class Choice { REPLACE, KEEP_BOTH, SKIP }

    class Answer(val choice: Choice, val forAll: Boolean)

    /** What a job asks: a name already taken ([password] false) or an archive's password. */
    class Question internal constructor(val password: Boolean, val name: String, val folder: String, val wrong: Boolean, val isFolder: Boolean) {
        private val latch = CountDownLatch(1)

        @Volatile
        var answered = false
            private set

        @Volatile
        private var answer: Any? = null

        /** The user's answer: an [Answer], a password, or null to cancel the job. */
        fun reply(a: Any?) {
            if (answered) return
            answered = true
            answer = a
            latch.countDown()
            changed()
        }

        internal fun await(job: Job): Any? {
            while (!latch.await(300, TimeUnit.MILLISECONDS)) {
                if (job.cancelled) return null
            }
            return answer
        }
    }

    class Job internal constructor(val kind: Kind, val title: String, internal val work: (Work) -> Unit) {
        val id = ids.incrementAndGet()

        @Volatile
        var cancelled = false
            private set

        @Volatile
        var running = false
            internal set

        @Volatile
        var finished = false
            internal set

        /** Still counting what there is to do. */
        @Volatile
        var counting = false
            internal set

        /** The file being done now. */
        @Volatile
        var current = ""
            internal set

        @Volatile
        var filesDone = 0
            internal set

        /** -1 while not known. */
        @Volatile
        var filesTotal = -1
            internal set

        @Volatile
        var bytesDone = 0L
            internal set

        @Volatile
        var bytesTotal = -1L
            internal set

        /** 0..1 when known, otherwise -1. */
        @Volatile
        var fraction = -1f
            internal set

        @Volatile
        var question: Question? = null
            internal set

        /** The outcome in a sentence ("Copied 12 items"). */
        @Volatile
        var message: String? = null
            internal set

        @Volatile
        var failed = false
            internal set

        var skipped = 0
            internal set

        /** What is worth showing afterwards: an extracted folder, a new archive, the file to open. */
        @Volatile
        var result: File? = null
            internal set

        /** Folders whose contents changed: the screen reloads when it shows one of them. */
        val touched: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        /** Runs on the main thread when the job is over, for the screen that started it. */
        var onDone: ((Job) -> Unit)? = null

        internal var forAll: Choice? = null

        fun cancel() {
            cancelled = true
            question?.reply(null)
            changed()
        }
    }

    private val ids = AtomicInteger()
    private val executor = Background.single("file-jobs")

    /** Opening a file from an archive doesn't wait behind a long copy. */
    private val opener = Background.single("file-open")
    private val main = Handler(Looper.getMainLooper())
    private val jobs = CopyOnWriteArrayList<Job>()
    private val screens = CopyOnWriteArrayList<() -> Unit>()
    private val service = CopyOnWriteArrayList<() -> Unit>()
    private var changePosted = false
    private var app: Context? = null

    /** Waiting and running jobs, the running one first. */
    val active: List<Job> get() = jobs.toList()

    val running: Job? get() = jobs.firstOrNull { it.running && it.kind != Kind.OPEN }

    /** The job asking something now, if any. */
    val asking: Job? get() = jobs.firstOrNull { it.question?.answered == false }

    /** Whether a Files screen is on show (it tells the outcome then; otherwise a notification does). */
    val watched: Boolean get() = screens.isNotEmpty()

    /** A screen showing the jobs: [l] runs on the main thread after each change. */
    fun addScreen(l: () -> Unit) = screens.add(l)

    fun removeScreen(l: () -> Unit) = screens.remove(l)

    internal fun addService(l: () -> Unit) = service.add(l)

    internal fun removeService(l: () -> Unit) = service.remove(l)

    /** Queues a job; [work] runs on the job thread. */
    fun start(ctx: Context, kind: Kind, title: String, onDone: ((Job) -> Unit)? = null, work: (Work) -> Unit): Job {
        val c = ctx.applicationContext
        app = c
        val job = Job(kind, title, work)
        job.onDone = onDone
        jobs += job
        L.d("File job queued: ${kind.name.lowercase()}")
        if (kind != Kind.OPEN) FileJobService.start(c)
        (if (kind == Kind.OPEN) opener else executor).execute { run(c, job) }
        changedNow()
        return job
    }

    private fun run(ctx: Context, job: Job) {
        if (!job.cancelled) {
            job.running = true
            changedNow()
            val w = Work(ctx, job)
            val started = SystemClock.uptimeMillis()
            try {
                job.work(w)
                if (job.cancelled) throw Cancelled()
                if (job.message == null) job.message = job.kind.done
            } catch (_: Cancelled) {
                // 3.0: how far it got, and where that is ("Cancelled after 12 of 40 files…").
                job.message = w.cancelledText()
            } catch (e: FileProblem) {
                job.failed = true
                job.message = e.message
                L.w("File job stopped: ${e.message}")
            } catch (e: OutOfMemoryError) {
                job.failed = true
                job.message = "This is too big for the phone's memory."
                L.w("File job ran out of memory", e)
            } catch (e: Throwable) {
                job.failed = true
                job.message = w.failureText(e)
                L.w("File job failed (${job.kind.name.lowercase()}, ${job.current})", e)
            } finally {
                w.finish()
                runCatching { Parts.compactIfBig(ctx) }
                // 3.0.4: the folders it changed (also when it stopped half-way) are measured again.
                FolderSizes.changed(synchronized(job.touched) { job.touched.toList() } + listOfNotNull(job.result?.path))
            }
            L.d("File job ${job.kind.name.lowercase()} over in ${SystemClock.uptimeMillis() - started} ms: ${job.filesDone} files, ${job.skipped} skipped, ${if (job.failed) "failed" else "ok"}")
        } else {
            job.message = "Cancelled"
        }
        job.running = false
        job.finished = true
        jobs.remove(job)
        main.post {
            job.onDone?.invoke(job)
            if (!watched && job.kind != Kind.OPEN) FileJobService.notifyDone(ctx, job)
            tellAll()
        }
    }

    /** Something changed: screens and the notification follow (a few times a second at most). */
    fun changed() {
        synchronized(this) {
            if (changePosted) return
            changePosted = true
        }
        main.postDelayed({
            synchronized(this) { changePosted = false }
            tellAll()
        }, 180)
    }

    private fun changedNow() = main.post { tellAll() }

    private fun tellAll() {
        screens.forEach { it() }
        service.forEach { it() }
    }

    internal fun questionAsked(job: Job) {
        changedNow()
        if (!watched) app?.let { c -> main.post { FileJobService.start(c) } }
    }

    /**
     * What a job's work uses while it runs: progress, questions to the user, and writing files with
     * the bytes counted, a limit on them (free space, archive bombs) and Cancel.
     */
    class Work internal constructor(val ctx: Context, val job: Job) {
        /** Files made, for the phone's media index. */
        private val made = ArrayList<String>()

        /** Most bytes this job may write: free space less a margin. */
        var limit = Long.MAX_VALUE

        var written = 0L
            private set

        private var lastTick = 0L

        /** 3.0: where the job puts things, for the outcome of a Cancel ("…are in “Download”"). */
        private var destination: File? = null

        /** 3.0: the bytes of the file being written now, before it (for "FAT32 can't hold files over 4 GB"). */
        private var fileStart = 0L

        /** 3.0: files a move copied but couldn't take from where they were (they're in both places). */
        internal var stayedBehind = 0

        /** 3.0: the watchdog gave up on this job's archive reader: it stops at its next step. */
        @Volatile
        internal var abandoned = false

        fun check() {
            if (job.cancelled || abandoned) throw Cancelled()
        }

        fun destination(dir: File?) {
            destination = dir
        }

        fun current(name: String) {
            job.current = name
            tick()
        }

        fun counting() {
            job.counting = true
            changed()
        }

        /** Progress counted in files (deleting, tar files) rather than bytes. */
        private var byFiles = false

        fun totals(files: Int, bytes: Long) {
            job.filesTotal = files
            job.bytesTotal = bytes
            job.counting = false
            byFiles = bytes <= 0 && files > 0
            changed()
        }

        /** 3.0: more to do than counted at first (a move that couldn't rename copies after all). */
        fun addTotals(files: Int, bytes: Long) {
            if (job.filesTotal >= 0) job.filesTotal = (job.filesTotal + files).coerceAtLeast(job.filesDone)
            if (bytes > 0) job.bytesTotal = job.bytesTotal.coerceAtLeast(0) + bytes
            changed()
        }

        fun fileDone() {
            job.filesDone++
            if (byFiles) job.fraction = (job.filesDone.toFloat() / job.filesTotal).coerceIn(0f, 1f)
            tick()
        }

        /** [n] more bytes done (of [Job.bytesTotal]). */
        fun progress(n: Long) {
            job.bytesDone += n
            if (job.bytesTotal > 0) job.fraction = (job.bytesDone.toFloat() / job.bytesTotal).coerceIn(0f, 1f)
            tick()
        }

        fun fraction(f: Float) {
            byFiles = false
            job.fraction = f.coerceIn(0f, 1f)
            tick()
        }

        fun skipped() {
            job.skipped++
        }

        fun result(f: File) {
            job.result = f
        }

        fun message(text: String) {
            job.message = text
        }

        fun touched(dir: File?) {
            dir?.let { job.touched += it.path }
        }

        fun made(f: File) {
            made += f.path
        }

        private fun tick() {
            val now = SystemClock.uptimeMillis()
            if (now - lastTick < 200) return
            lastTick = now
            changed()
        }

        /**
         * The name to write [target] under: itself (new, or replacing), a free name ("photo (1).jpg"), or
         * null to skip it. The user is asked when the name is taken, unless they answered "for all".
         * Folders that are there already are merged.
         */
        fun resolve(target: File, isDir: Boolean, sameAsSource: Boolean = false): File? {
            if (!target.exists()) return target
            // Pasted into its own folder: a copy beside it ("photo (1).jpg", "Holiday (1)").
            if (sameAsSource) return FileOps.freeName(target)
            if (isDir && target.isDirectory) return target
            if (isDir != target.isDirectory) return FileOps.freeName(target, isDir)
            val choice = job.forAll ?: run {
                val a = ask(Question(false, target.name, target.parentFile?.name.orEmpty(), false, isDir)) as? Answer ?: throw Cancelled()
                if (a.forAll) job.forAll = a.choice
                a.choice
            }
            return when (choice) {
                Choice.REPLACE -> target
                Choice.KEEP_BOTH -> FileOps.freeName(target)
                Choice.SKIP -> {
                    job.skipped++
                    null
                }
            }
        }

        /** Asks for [archive]'s password ([wrong]: the last one didn't work). Null: the user cancelled. */
        /** Names already taken are replaced without asking (a file opened from an archive again). */
        fun replaceAll() {
            job.forAll = Choice.REPLACE
        }

        fun askPassword(archive: String, wrong: Boolean): String =
            (ask(Question(true, archive, "", wrong, false)) as? String)?.takeIf { it.isNotEmpty() } ?: throw Cancelled()

        private fun ask(q: Question): Any? {
            job.question = q
            questionAsked(job)
            val a = q.await(job)
            job.question = null
            changed()
            check()
            return a
        }

        /**
         * Writes [target] through [access]: [fill] writes its bytes. Bytes written count towards
         * [limit] (and the progress, when [counted]); what was written is deleted when it fails or
         * is cancelled. 3.0: always written aside first, under a hidden name (".swift-….part", see
         * Parts), and given its own name only once whole: a copy cut off half-way (Swift closed, the
         * battery) never shows as the file, and a file being replaced stays until the new one is
         * there, so a failure (no space, a wrong password, Cancel) keeps the old one.
         */
        fun write(target: File, access: FileAccess, time: Long, counted: Boolean = true, fill: (OutputStream) -> Unit) {
            val part = Parts.newIn(target.parentFile)
            beginPart(part)
            fileStart = written
            var ok = false
            try {
                Guarded(access.output(part), counted).use { fill(it) }
                ok = true
            } finally {
                if (!ok) dropPart(part, access)
            }
            swapIn(part, target, access, written - fileStart)
            access.setTime(target, time)
            made += target.path
        }

        /** 3.0: [part] is being written (see Parts). */
        internal fun beginPart(part: File) = Parts.begin(ctx, part)

        /** 3.0: [part] wasn't finished: it goes. */
        internal fun dropPart(part: File, access: FileAccess) {
            runCatching { access.delete(part) }
            Parts.end(ctx, part)
        }

        /**
         * 3.0: [part], written whole ([length] bytes; -1: not known), takes [target]'s name. It must be
         * there with every byte first; a file being replaced goes only then. If the last rename fails
         * (rare), the part is left as it is: the new copy isn't lost, and the message says its name.
         */
        internal fun swapIn(part: File, target: File, access: FileAccess, length: Long) {
            try {
                if (!part.isFile || (length >= 0 && part.length() != length)) {
                    runCatching { access.delete(part) }
                    throw FileProblem("Swift couldn't finish “${target.name}”: the copy didn't come out whole. The storage may be full or failing.")
                }
                val replacing = target.exists()
                if (replacing) {
                    // Never a folder in a file's place (one may have been made there meanwhile).
                    if (target.isDirectory || !access.delete(target)) {
                        runCatching { access.delete(part) }
                        throw FileProblem("Swift couldn't replace “${target.name}”.")
                    }
                }
                if (!access.rename(part, target.name)) {
                    throw FileProblem(
                        if (replacing) "Swift couldn't replace “${target.name}”. The new copy is “${part.name}”, a hidden file in the same folder."
                        else "Swift couldn't give “${target.name}” its name. It is “${part.name}”, a hidden file in the same folder.",
                    )
                }
            } finally {
                Parts.end(ctx, part)
            }
        }

        /** Copies [input] to [out] in large pieces. */
        fun copy(input: InputStream, out: OutputStream) {
            val buf = ByteArray(BUFFER)
            while (true) {
                check()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }

        /** Copies [input] to [out], counting what was read as progress (compressing). */
        fun copyCountingInput(input: InputStream, out: OutputStream) {
            val buf = ByteArray(BUFFER)
            while (true) {
                check()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                progress(n.toLong())
            }
        }

        /** An output that counts its bytes against [limit] and stops at Cancel. */
        inner class Guarded(out: OutputStream, private val counted: Boolean) : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                check()
                if (written + len > limit) throw FileProblem(NO_ROOM)
                out.write(b, off, len)
                written += len
                if (counted) progress(len.toLong())
            }

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        }

        /**
         * 3.0: a file opened for writing anywhere in it (7z goes back to its start at the end), its
         * bytes counted against [limit] as [Guarded]'s; [extent] is how long the file is.
         */
        inner class GuardedChannel(private val ch: SeekableByteChannel) : SeekableByteChannel {
            var extent = 0L
                private set

            override fun write(src: ByteBuffer): Int {
                check()
                val len = src.remaining()
                if (written + len > limit) throw FileProblem(NO_ROOM)
                val n = ch.write(src)
                written += n
                extent = maxOf(extent, ch.position())
                return n
            }

            override fun read(dst: ByteBuffer): Int = ch.read(dst)

            override fun position(): Long = ch.position()

            override fun position(newPosition: Long): SeekableByteChannel {
                ch.position(newPosition)
                return this
            }

            override fun size(): Long = ch.size()

            override fun truncate(size: Long): SeekableByteChannel {
                ch.truncate(size)
                extent = minOf(extent, size)
                return this
            }

            override fun isOpen(): Boolean = ch.isOpen

            override fun close() = ch.close()
        }

        /** 3.0: the usual reasons in words (FileErrors): "The phone is full", "FAT32 can't hold files over 4 GB"… */
        internal fun failureText(e: Throwable): String {
            val name = job.current.ifBlank { null }
            val onCard = destination?.let { Volumes.isCard(ctx, it) } == true
            FileErrors.text(e, written - fileStart, onCard)?.let { return it }
            return when {
                name != null -> "Swift couldn't finish with “$name”. It may be damaged, or no longer there."
                else -> "Swift couldn't finish this."
            }
        }

        /** 3.0: what a Cancel left: "Cancelled after 12 of 40 files. They're in “Download”…". */
        internal fun cancelledText(): String {
            val n = job.filesDone
            val to = destination
            if (n <= 0 || to == null || job.kind !in setOf(Kind.COPY, Kind.MOVE, Kind.EXTRACT)) return "Cancelled"
            val of = if (job.filesTotal > n) " of ${job.filesTotal}" else ""
            val place = when {
                PrivateFolder.isInside(ctx, to) -> "Private"
                Volumes.of(ctx, to) == null -> to.name
                else -> FileIndex.placeOf(ctx, to)
            }
            val rest = if (job.kind == Kind.EXTRACT) "" else " The rest are where they were."
            return "Cancelled after $n$of " + (if (n == 1 && of.isEmpty()) "file" else "files") + ". " +
                (if (n == 1) "It's" else "They're") + " in “$place”." + rest
        }

        internal fun finish() {
            FileOps.scan(ctx, made)
        }
    }

    /** "12 items", "1 item". */
    fun items(n: Int) = if (n == 1) "1 item" else "$n items"

    /** 3.0: room always left for the phone itself when copying, extracting, compressing or receiving files. */
    const val MARGIN = 50L * 1_048_576

    private const val NO_ROOM = "There isn't enough free space for this."

    /** "3 of 12 files · 45 MB of 120 MB" and the like, for the progress. */
    fun detail(job: Job): String = buildString {
        when {
            job.question?.password == true -> append("Waiting for the password")
            job.question != null -> append("Waiting for your answer")
            job.counting -> append("Counting…")
            else -> {
                if (job.filesTotal > 0) append("${job.filesDone.coerceAtMost(job.filesTotal)} of ${job.filesTotal}")
                else if (job.filesDone > 0) append(items(job.filesDone))
                if (job.bytesTotal > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(UrlUtils.formatBytes(job.bytesDone.coerceAtMost(job.bytesTotal))).append(" of ").append(UrlUtils.formatBytes(job.bytesTotal))
                } else if (job.bytesDone > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(UrlUtils.formatBytes(job.bytesDone))
                }
            }
        }
    }

    const val BUFFER = 65536
}
