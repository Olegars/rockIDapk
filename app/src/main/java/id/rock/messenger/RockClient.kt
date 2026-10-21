package id.rock.messenger

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class Person(
    val id: Long,
    val username: String,
    val displayName: String,
    val online: Boolean = false,
    val role: String = "",
)

data class ChatMessage(
    val id: Long,
    val dialogId: Long,
    val senderId: Long,
    val text: String,
    val date: Long,
    val kind: String = "text",
    val replyTo: Long = 0,
    val quote: String = "",
    val fileId: Long = 0,
    val silent: Boolean = false,
    val editedAt: Long = 0,
    val entities: String = "",
    val views: Int = 0,
    val expiresAt: Long = 0,
    val topicId: Long = 0,
    val noForward: Boolean = false,
    val transcript: String = "",
    val translation: String = "",
    val reactions: String = "",
)

data class DialogRow(
    val id: Long,
    val title: String,
    val preview: String,
    val time: String,
    val unread: Int,
    val online: Boolean,
    val group: Boolean,
    val seed: Long,
    val type: String = "private",
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val muted: Boolean = false,
)

sealed class ChatLine {
    data class Older(val loading: Boolean) : ChatLine()
    data class Day(val label: String) : ChatLine()
    data class Bubble(
        val id: Long,
        val text: String,
        val time: String,
        val mine: Boolean,
        val showName: Boolean,
        val sender: String,
        val seed: Long,
    ) : ChatLine()
}

/**
 * Один TCP-сокет к rockIDserver: uint32 BE длина + JSON.
 * Сокет прямой, без HTTP-прокси.
 */
class RockClient(
    context: Context,
    private val callback: Callback,
) {
    interface Callback {
        fun onLink(online: Boolean, text: String)
        fun onSession(me: Person?, restoring: Boolean)
        fun onAuthError(text: String)
        fun onDialogs(rows: List<DialogRow>)
        fun onChat(dialogId: Long, lines: List<ChatLine>, stickToEnd: Boolean)
        fun onPeople(people: List<Person>, contacts: Boolean)
        fun onMembers(people: List<Person>, myRole: String)
        fun onNotice(text: String)
        fun onCloudPassword(challenge: String) {}
        fun onBoard(title: String, rows: List<String>) {}
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("rockid", Context.MODE_PRIVATE)
    private val aes = HashMap<Long, ByteArray>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val watch = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    private val state = Any()
    private val pending = ConcurrentHashMap<Int, Pending>()

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var nextId = 0
    private var authGen = 0
    private var searchGen = 0
    private var historyGen = 0
    private var retryMs = 1000L

    var host: String = prefs.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
        private set
    var port: Int = prefs.getInt("port", DEFAULT_PORT)
        private set
    var me: Person? = null
        private set
    var activeDialogId: Long = 0
        private set

    private var token: String = prefs.getString("token", "") ?: ""
    private var compress = false
    private var xPriv = ByteArray(0)
    private val delivered = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private var queued: Queued = Queued.None
    private var qUser = ""
    private var qPass = ""
    private var qName = ""
    private val dialogs = mutableListOf<Dialog>()
    private val messages = HashMap<Long, MutableList<ChatMessage>>()
    private val hasMore = HashMap<Long, Boolean>()
    private val readCursor = HashMap<Long, Long>()
    private val users = HashMap<Long, Person>()
    private val fetchingUsers = HashSet<Long>()
    private var contacts = listOf<Person>()
    private var historyLoading = false

    init {
        if (host.isBlank() || host == "127.0.0.1" || host.equals("localhost", true)) {
            host = DEFAULT_HOST
        }
        if (port !in 1..65535) port = DEFAULT_PORT
        val saved = prefs.getString("x25519", "").orEmpty()
        if (saved.isNotEmpty()) {
            val raw = Base64.decode(saved, Base64.NO_WRAP)
            if (raw.size == 32) xPriv = raw
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (token.isNotEmpty()) post { callback.onSession(null, true) }
        io.execute { loop() }
        watch.scheduleAtFixedRate({ watchdog() }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun stop() {
        running.set(false)
        closeSocket()
        io.shutdownNow()
        watch.shutdownNow()
    }

    fun setEndpoint(nextHost: String, nextPort: Int) {
        val clean = nextHost.trim().ifBlank { DEFAULT_HOST }
        val p = if (nextPort in 1..65535) nextPort else DEFAULT_PORT
        val changed = clean != host || p != port
        host = clean
        port = p
        prefs.edit().putString("host", host).putInt("port", port).apply()
        if (changed) {
            closeSocket()
        }
    }

    fun signIn(username: String, password: String) {
        val name = username.trim()
        validateUsername(name)?.let { post { callback.onAuthError(it) }; return }
        validatePassword(password)?.let { post { callback.onAuthError(it) }; return }
        synchronized(writeLock) {
            authGen++
            queued = Queued.SignIn
            qUser = name
            qPass = password
            qName = ""
            writeAuthLocked()
        }
    }

    fun signUp(username: String, password: String, displayName: String) {
        val name = username.trim()
        val display = displayName.trim()
        validateUsername(name)?.let { post { callback.onAuthError(it) }; return }
        validatePassword(password)?.let { post { callback.onAuthError(it) }; return }
        validateDisplay(display)?.let { post { callback.onAuthError(it) }; return }
        synchronized(writeLock) {
            authGen++
            queued = Queued.SignUp
            qUser = name
            qPass = password
            qName = display
            writeAuthLocked()
        }
    }

    fun signOut() {
        synchronized(writeLock) {
            authGen++
            queued = Queued.None
            if (output != null && me != null) {
                callLocked("auth.signOut", JSONObject()) {}
            }
            token = ""
            prefs.edit().remove("token").apply()
        }
        clearChat()
        me = null
        post { callback.onSession(null, false) }
    }

    fun forgetSession() {
        synchronized(writeLock) {
            authGen++
            queued = Queued.None
            token = ""
            prefs.edit().remove("token").apply()
        }
        clearChat()
        me = null
        post { callback.onSession(null, false) }
    }

    fun refresh() {
        if (me == null) return
        requestDialogs()
        requestContacts()
        if (activeDialogId > 0) requestHistory(activeDialogId, older = false)
    }

    fun openDialog(id: Long) {
        val lines = synchronized(state) {
            activeDialogId = id
            linesFor(id)
        }
        post { callback.onChat(id, lines, true) }
        requestHistory(id, older = false)
        val kind = synchronized(state) { dialogs.firstOrNull { it.id == id }?.type }
        if (kind == "secret") ensureSecret(id)
    }

    fun savedSession(): Boolean = token.isNotEmpty()

    fun loadOlder() {
        val id = synchronized(state) {
            val current = activeDialogId
            if (current <= 0 || historyLoading || hasMore[current] != true) 0L else current
        }
        if (id > 0) requestHistory(id, older = true)
    }

    fun sendMessage(text: String) = sendRich(text, 0, "", silent = false, scheduleAt = 0, topicId = 0, entities = "")

    fun sendRich(
        text: String,
        replyTo: Long,
        quote: String,
        silent: Boolean,
        scheduleAt: Long,
        topicId: Long,
        entities: String,
        kind: String = "text",
        fileId: Long = 0,
    ): Boolean {
        val id = synchronized(state) { activeDialogId }
        if (id <= 0) return false
        val media = kind != "text" && kind != "sponsor"
        var body = text
        if (!media && body.trim().isEmpty()) return false
        if (body.toByteArray(Charsets.UTF_8).size > if (media) 65536 else 4096) {
            post { callback.onNotice("Слишком длинный текст") }
            return false
        }
        if (hasControls(body, allowBreaks = true)) {
            post { callback.onNotice("В тексте есть запрещённый символ") }
            return false
        }
        val secret = synchronized(state) { dialogs.firstOrNull { it.id == id }?.type == "secret" }
        if (secret && kind == "text") ensureSecret(id)
        if (output == null) {
            post { callback.onNotice("Нет соединения") }
            return false
        }
        val params = JSONObject().put("dialog_id", id).put("kind", kind)
        if (body.isNotEmpty()) params.put("text", body)
        if (fileId > 0) params.put("file_id", fileId)
        if (replyTo > 0) params.put("reply_to", replyTo)
        if (quote.isNotBlank()) params.put("quote", quote.take(256))
        if (silent) params.put("silent", true)
        if (scheduleAt > 0) params.put("schedule_at", scheduleAt)
        if (topicId > 0) params.put("topic_id", topicId)
        if (entities.isNotEmpty()) params.put("entities", entities)
        call("messages.send", params) { response ->
            if (!response.optBoolean("ok")) {
                val code = errorCode(response)
                if (code == "UNAUTHORIZED") loseSession() else post { callback.onNotice(humanize(code)) }
                return@call
            }
            val message = messageOf(response.getJSONObject("result"))
            store(message)
            touch(message, unreadDelta = 0)
            if (message.dialogId == activeDialogId) {
                markRead(message.dialogId)
                publishChat(message.dialogId, true)
            }
        }
        return true
    }

    fun search(query: String) {
        val needle = query.trim()
        val gen = ++searchGen
        if (needle.isEmpty()) {
            post { callback.onPeople(contacts, true) }
            return
        }
        if (needle.toByteArray(Charsets.UTF_8).size > 32) {
            post { callback.onNotice("Запрос длиннее 32 байт") }
            return
        }
        val params = JSONObject().put("query", needle).put("limit", 20)
        call("users.search", params) { response ->
            if (gen != searchGen) return@call
            if (!response.optBoolean("ok")) {
                if (errorCode(response) == "UNAUTHORIZED") loseSession()
                return@call
            }
            val people = response.getJSONObject("result").optJSONArray("users").toPeople()
            people.forEach { cache(it) }
            post { callback.onPeople(people, false) }
        }
    }

    fun startPrivate(userId: Long) {
        if (userId <= 0 || userId == me?.id) {
            post { callback.onNotice("Нельзя написать самому себе") }
            return
        }
        call("dialogs.createPrivate", JSONObject().put("user_id", userId)) { response ->
            if (!response.optBoolean("ok")) {
                val code = errorCode(response)
                if (code == "UNAUTHORIZED") loseSession() else post { callback.onNotice(humanize(code)) }
                return@call
            }
            val dialog = upsert(response.getJSONObject("result"))
            openDialog(dialog.id)
        }
    }

    fun createGroup(title: String, memberIds: List<Long>) {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed.toByteArray(Charsets.UTF_8).size > 128 || hasControls(trimmed, false)) {
            post { callback.onNotice("Название группы: 1–128 символов") }
            return
        }
        val ids = JSONArray()
        memberIds.distinct().filter { it > 0 && it != me?.id }.take(199999).forEach { ids.put(it) }
        val params = JSONObject().put("title", trimmed).put("member_ids", ids)
        call("dialogs.createGroup", params) { response ->
            if (!response.optBoolean("ok")) {
                val code = errorCode(response)
                if (code == "UNAUTHORIZED") loseSession() else post { callback.onNotice(humanize(code)) }
                return@call
            }
            val dialog = upsert(response.getJSONObject("result"))
            openDialog(dialog.id)
        }
    }

    fun loadMembers() {
        val id = activeDialogId
        if (id <= 0) return
        call("dialogs.members", JSONObject().put("dialog_id", id)) { response ->
            if (id != activeDialogId) return@call
            if (!response.optBoolean("ok")) {
                if (errorCode(response) == "UNAUTHORIZED") loseSession()
                return@call
            }
            val people = mutableListOf<Person>()
            var role = "member"
            val arr = response.getJSONObject("result").optJSONArray("members") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val person = personOf(arr.getJSONObject(i))
                cache(person)
                people += person
                if (person.id == me?.id) role = person.role.ifBlank { "member" }
            }
            val myRole = role
            post { callback.onMembers(people, myRole) }
        }
    }

    fun addMember(userId: Long) {
        val id = activeDialogId
        if (id <= 0 || userId <= 0) return
        val params = JSONObject().put("dialog_id", id).put("user_id", userId)
        call("dialogs.addMember", params) { response ->
            if (!response.optBoolean("ok")) {
                val code = errorCode(response)
                if (code == "UNAUTHORIZED") loseSession() else post { callback.onNotice(humanize(code)) }
                return@call
            }
            val result = response.getJSONObject("result")
            result.optJSONObject("dialog")?.let { upsert(it) }
            loadMembers()
            post {
                callback.onNotice(if (result.optBoolean("added")) "Участник добавлен" else "Уже в группе")
            }
        }
    }

    fun addContact(userId: Long) {
        if (userId <= 0) return
        call("contacts.add", JSONObject().put("user_id", userId)) { response ->
            if (!response.optBoolean("ok")) {
                val code = errorCode(response)
                if (code == "UNAUTHORIZED") loseSession() else post { callback.onNotice(humanize(code)) }
                return@call
            }
            cache(personOf(response.getJSONObject("result")))
            requestContacts()
            post { callback.onNotice("Контакт добавлен") }
        }
    }

    private fun loop() {
        while (running.get()) {
            try {
                val next = Socket()
                next.tcpNoDelay = true
                next.keepAlive = true
                next.connect(InetSocketAddress(host, port), 8000)
                socket = next
                retryMs = 1000
                post { callback.onLink(true, "Сервер доступен") }
                synchronized(writeLock) {
                    output = next.getOutputStream()
                    writeAuthLocked()
                }
                readLoop(next.getInputStream())
            } catch (_: Exception) {
            } finally {
                closeSocket()
                failPending("NOT_CONNECTED")
                if (!running.get()) break
                post { callback.onLink(false, "Нет связи, повторяем…") }
                try {
                    Thread.sleep(retryMs)
                } catch (_: InterruptedException) {
                    break
                }
                retryMs = (retryMs * 2).coerceAtMost(15000)
            }
        }
    }

    private fun readLoop(input: InputStream) {
        val framer = Framer()
        val chunk = ByteArray(8192)
        while (running.get()) {
            val n = input.read(chunk)
            if (n < 0) break
            for (frame in framer.push(chunk, n)) handleFrame(frame)
        }
    }

    private fun handleFrame(payload: ByteArray) {
        val obj = try {
            JSONObject(payload.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        synchronized(state) {
            if (obj.has("update")) {
                handleUpdate(obj.optString("update"), obj.optJSONObject("data") ?: JSONObject())
                return
            }
            val id = obj.optInt("id", -1)
            val call = pending.remove(id) ?: return
            try {
                call.callback(obj)
            } catch (_: Exception) {
            }
        }
    }

    private fun handleUpdate(type: String, data: JSONObject) {
        when (type) {
            "message.new" -> {
                val message = messageOf(data)
                store(message)
                val viewing = message.dialogId == activeDialogId
                val delta = if (!viewing && message.senderId != me?.id && !message.silent) 1 else 0
                if (dialogs.none { it.id == message.dialogId }) requestDialogs() else touch(message, delta)
                if (viewing) {
                    markRead(message.dialogId)
                    publishChat(message.dialogId, message.senderId == me?.id)
                } else if (message.senderId != me?.id && !message.silent && !isMuted(message.dialogId)) {
                    post { callback.onNotice(previewOf(message.dialogId, message.text).take(80)) }
                }
                if (message.senderId != me?.id && message.id > 0) {
                    rpc("messages.ack", JSONObject().put("message_id", message.id)) {}
                }
            }
            "message.delivered" -> {
                val messageId = data.optLong("message_id")
                if (messageId > 0) delivered.add(messageId)
                val dialogId = data.optLong("dialog_id")
                if (dialogId == activeDialogId) publishChat(dialogId, false)
            }
            "call.media" -> playCallAudio(data)
            "message.edit" -> {
                val message = messageOf(data)
                replace(message)
                if (message.dialogId == activeDialogId) publishChat(message.dialogId, false)
            }
            "message.deleted" -> {
                erase(data.optLong("dialog_id"), data.optLong("message_id"))
            }
            "message.reaction" -> applyReaction(data)
            "screenshot", "story.screenshot" -> post { callback.onNotice("Собеседник сделал скриншот") }
            "call.update" -> post { callback.onBoard("Эфир", listOf("id ${data.optLong("id")} · участников ${data.optJSONArray("peers")?.length() ?: 0}")) }
            "dialog.new" -> upsert(data)
            "dialog.member" -> loadMembers()
            "presence" -> {
                val userId = data.optLong("user_id")
                val online = data.optBoolean("online")
                users[userId]?.let { users[userId] = it.copy(online = online) }
                for (i in dialogs.indices) {
                    val peer = dialogs[i].peer ?: continue
                    if (peer.id == userId) dialogs[i] = dialogs[i].copy(peer = peer.copy(online = online))
                }
                contacts = contacts.map { if (it.id == userId) it.copy(online = online) else it }
                publishDialogs()
                if (activeDialogId > 0) publishChat(activeDialogId, false)
            }
        }
    }

    private fun writeAuthLocked() {
        val out = output ?: return
        val gen = authGen
        when (queued) {
            Queued.SignIn -> {
                queued = Queued.None
                val params = JSONObject().put("username", qUser).put("password", qPass)
                    .put("device", android.os.Build.MODEL.take(64))
                    .put("device_id", deviceId())
                    .put("platform", "android")
                qPass = ""
                callLocked("auth.signIn", params) { response ->
                    if (gen != authGen) return@callLocked
                    if (!response.optBoolean("ok")) {
                        post { callback.onAuthError(humanize(errorCode(response))) }
                        return@callLocked
                    }
                    val result = response.getJSONObject("result")
                    if (result.optBoolean("need_cloud_password")) {
                        post { callback.onCloudPassword(result.optString("challenge")) }
                        return@callLocked
                    }
                    applySession(personOf(result.getJSONObject("user")), result.optString("token"))
                }
            }
            Queued.SignUp -> {
                queued = Queued.None
                val params = JSONObject()
                    .put("username", qUser)
                    .put("password", qPass)
                    .put("display_name", qName)
                    .put("device", android.os.Build.MODEL.take(64))
                    .put("device_id", deviceId())
                    .put("platform", "android")
                qPass = ""
                callLocked("auth.signUp", params) { response ->
                    if (gen != authGen) return@callLocked
                    if (!response.optBoolean("ok")) {
                        post { callback.onAuthError(humanize(errorCode(response))) }
                        return@callLocked
                    }
                    val result = response.getJSONObject("result")
                    applySession(personOf(result.getJSONObject("user")), result.optString("token"))
                }
            }
            Queued.None -> {
                if (token.isNotEmpty()) {
                    callLocked("auth.bind", JSONObject().put("token", token)) { response ->
                        if (gen != authGen) return@callLocked
                        if (!response.optBoolean("ok")) {
                            val code = errorCode(response)
                            if (code == "INVALID_TOKEN" || code == "UNAUTHORIZED") {
                                token = ""
                                prefs.edit().remove("token").apply()
                                me = null
                                post { callback.onSession(null, false) }
                                post { callback.onAuthError("Сессия истекла, войдите снова") }
                            }
                            return@callLocked
                        }
                        val user = personOf(response.getJSONObject("result").getJSONObject("user"))
                        if (me == null) applySession(user, "") else refresh()
                    }
                } else if (me == null) {
                    post { callback.onSession(null, false) }
                }
            }
        }
    }

    private fun applySession(user: Person, newToken: String) {
        clearChat()
        if (newToken.isNotEmpty()) {
            token = newToken
            prefs.edit().putString("token", token).apply()
        }
        me = user
        cache(user)
        post { callback.onSession(user, false) }
        requestDialogs()
        requestContacts()
        armSession()
    }

    private fun requestDialogs() {
        call("dialogs.get", JSONObject()) { response ->
            if (!response.optBoolean("ok")) {
                if (errorCode(response) == "UNAUTHORIZED") loseSession()
                return@call
            }
            val arr = response.getJSONObject("result").optJSONArray("dialogs") ?: JSONArray()
            val next = mutableListOf<Dialog>()
            for (i in 0 until arr.length()) next += dialogOf(arr.getJSONObject(i))
            dialogs.clear()
            dialogs += next
            if (activeDialogId > 0 && dialogs.none { it.id == activeDialogId }) activeDialogId = 0
            publishDialogs()
        }
    }

    private fun requestContacts() {
        call("contacts.get", JSONObject()) { response ->
            if (!response.optBoolean("ok")) return@call
            contacts = response.getJSONObject("result").optJSONArray("contacts").toPeople()
            contacts.forEach { cache(it) }
            post { callback.onPeople(contacts, true) }
        }
    }

    private fun requestHistory(dialogId: Long, older: Boolean) {
        var before = 0L
        val gen: Int
        synchronized(state) {
            if (older) {
                before = messages[dialogId]?.firstOrNull()?.id ?: return
                if (before <= 0) return
            }
            gen = ++historyGen
            historyLoading = true
            if (dialogId == activeDialogId) publishChat(dialogId, !older)
        }
        val params = JSONObject().put("dialog_id", dialogId).put("limit", PAGE)
        if (older) params.put("before_id", before)
        call("messages.getHistory", params) { response ->
            if (gen != historyGen) return@call
            historyLoading = false
            if (!response.optBoolean("ok")) {
                if (errorCode(response) == "UNAUTHORIZED") loseSession()
                if (dialogId == activeDialogId) publishChat(dialogId, false)
                return@call
            }
            val arr = response.getJSONObject("result").optJSONArray("messages") ?: JSONArray()
            val incoming = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) incoming += messageOf(arr.getJSONObject(i))
            val current = messages[dialogId] ?: mutableListOf()
            val merged = if (older) {
                (incoming + current).distinctBy { it.id }.sortedBy { it.id }.toMutableList()
            } else if (incoming.isEmpty()) {
                mutableListOf()
            } else {
                val maxIn = incoming.maxOf { it.id }
                (incoming + current.filter { it.id > maxIn }).distinctBy { it.id }.sortedBy { it.id }.toMutableList()
            }
            messages[dialogId] = merged
            hasMore[dialogId] = arr.length() >= PAGE
            if (dialogId == activeDialogId) {
                markRead(dialogId)
                publishChat(dialogId, !older)
            }
        }
    }

    private fun markRead(dialogId: Long) {
        if (dialogId != activeDialogId) return
        val maxId = messages[dialogId]?.lastOrNull()?.id ?: return
        if (maxId <= (readCursor[dialogId] ?: 0L)) return
        readCursor[dialogId] = maxId
        val index = dialogs.indexOfFirst { it.id == dialogId }
        if (index >= 0 && dialogs[index].unread != 0) {
            dialogs[index] = dialogs[index].copy(unread = 0)
            publishDialogs()
        }
        call("messages.read", JSONObject().put("dialog_id", dialogId).put("max_id", maxId)) {}
    }

    private fun store(message: ChatMessage) {
        val list = messages.getOrPut(message.dialogId) { mutableListOf() }
        if (list.any { it.id == message.id }) return
        list += message
        list.sortBy { it.id }
    }

    private fun touch(message: ChatMessage, unreadDelta: Int) {
        val index = dialogs.indexOfFirst { it.id == message.dialogId }
        if (index < 0) return
        val dialog = dialogs[index]
        dialogs[index] = dialog.copy(
            last = message,
            unread = (dialog.unread + unreadDelta).coerceAtLeast(0),
        )
        sortDialogs()
        publishDialogs()
    }

    private fun upsert(obj: JSONObject): Dialog {
        val dialog = dialogOf(obj)
        val index = dialogs.indexOfFirst { it.id == dialog.id }
        if (index >= 0) dialogs[index] = dialog else dialogs += dialog
        sortDialogs()
        publishDialogs()
        return dialog
    }

    var listMode: String = "Все"

    fun chooseList(mode: String) {
        listMode = mode
        publishDialogs()
    }

    private fun sortDialogs() {
        dialogs.sortWith(compareByDescending<Dialog> { it.pinned }.thenByDescending { it.last?.date ?: 0L }.thenByDescending { it.id })
    }

    private fun publishDialogs() {
        val rows = dialogs.filter { dialog ->
            when (listMode) {
                "Архив" -> dialog.archived
                "Непрочитанные" -> !dialog.archived && dialog.unread > 0
                else -> !dialog.archived
            }
        }.map { it.toRow() }
        post { callback.onDialogs(rows) }
    }

    private fun linesFor(dialogId: Long): List<ChatLine> {
        val list = messages[dialogId].orEmpty()
        val kind = dialogs.firstOrNull { it.id == dialogId }?.type
        val group = kind == "group" || kind == "channel"
        val out = mutableListOf<ChatLine>()
        if (hasMore[dialogId] == true || historyLoading) out += ChatLine.Older(historyLoading)
        var prevDay = -1
        var prevSender = -1L
        val now = System.currentTimeMillis() / 1000
        for (message in list) {
            if (message.expiresAt > 0 && now >= message.expiresAt) continue
            val day = dayKey(message.date)
            if (day != prevDay) {
                out += ChatLine.Day(dayLabel(message.date))
                prevDay = day
                prevSender = -1L
            }
            val mine = message.senderId == me?.id
            if (!mine) ensureUser(message.senderId)
            out += ChatLine.Bubble(
                id = message.id,
                text = present(message),
                time = clock(message.date),
                mine = mine,
                showName = group && !mine && message.senderId != prevSender,
                sender = userName(message.senderId),
                seed = message.senderId,
            )
            prevSender = message.senderId
        }
        return out
    }

    private fun ensureUser(id: Long) {
        if (id <= 0 || id == me?.id || users.containsKey(id) || !fetchingUsers.add(id)) return
        call("users.get", JSONObject().put("user_id", id)) { response ->
            fetchingUsers.remove(id)
            if (!response.optBoolean("ok")) return@call
            cache(personOf(response.getJSONObject("result")))
            publishDialogs()
            if (activeDialogId > 0) publishChat(activeDialogId, false)
        }
    }

    private fun call(method: String, params: JSONObject, callback: (JSONObject) -> Unit) {
        synchronized(writeLock) { callLocked(method, params, callback) }
    }

    private fun callLocked(method: String, params: JSONObject, callback: (JSONObject) -> Unit) {
        val out = output
        if (out == null) {
            val err = JSONObject().put("ok", false).put("error", JSONObject().put("message", "NOT_CONNECTED"))
            post { callback(err) }
            return
        }
        val id = ++nextId
        val req = JSONObject().put("id", id).put("method", method).put("params", params)
        pending[id] = Pending(method, System.currentTimeMillis() + 15000, callback)
        var body = req.toString().toByteArray(Charsets.UTF_8)
        var size = body.size
        if (compress) {
            val packed = deflate(body)
            if (packed.isNotEmpty() && packed.size < body.size) {
                body = packed
                size = body.size or 0x80000000.toInt()
            }
        }
        val head = ByteBuffer.allocate(4).putInt(size).array()
        out.write(head)
        out.write(body)
        out.flush()
    }

    private fun watchdog() {
        val now = System.currentTimeMillis()
        val expired = pending.filterValues { it.deadline in 1 until now }.keys.toList()
        var pingDead = false
        for (id in expired) {
            val item = pending.remove(id) ?: continue
            if (item.method == "ping") pingDead = true
            val err = JSONObject().put("ok", false).put("error", JSONObject().put("message", "TIMEOUT"))
            try {
                item.callback(err)
            } catch (_: Exception) {
            }
        }
        if (pingDead) closeSocket()
        if (output != null && pending.values.none { it.method == "ping" }) {
            call("ping", JSONObject()) {}
        }
    }

    private fun failPending(code: String) {
        val left = pending.values.toList()
        pending.clear()
        val err = JSONObject().put("ok", false).put("error", JSONObject().put("message", code))
        for (item in left) {
            try {
                item.callback(err)
            } catch (_: Exception) {
            }
        }
    }

    private fun loseSession() {
        synchronized(writeLock) {
            authGen++
            token = ""
            prefs.edit().remove("token").apply()
        }
        clearChat()
        me = null
        post { callback.onSession(null, false) }
        post { callback.onAuthError("Нужно войти снова") }
    }

    private fun clearChat() {
        synchronized(state) {
            dialogs.clear()
            messages.clear()
            hasMore.clear()
            readCursor.clear()
            users.clear()
            contacts = emptyList()
            activeDialogId = 0
        }
        post { callback.onDialogs(emptyList()) }
    }

    private fun closeSocket() {
        compress = false
        synchronized(writeLock) { output = null }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun cache(person: Person) {
        if (person.id <= 0) return
        users[person.id] = person
    }

    private fun userName(id: Long): String {
        if (id == me?.id) return me?.displayName?.ifBlank { me?.username }.orEmpty()
        val person = users[id] ?: return ""
        return person.displayName.ifBlank { person.username }
    }

    private fun dialogOf(obj: JSONObject): Dialog {
        val peer = obj.optJSONObject("peer")?.let { personOf(it) }
        peer?.let { cache(it) }
        val last = obj.optJSONObject("last_message")?.let { messageOf(it) }
        return Dialog(
            id = obj.optLong("id"),
            type = obj.optString("type"),
            title = obj.optString("title"),
            memberCount = obj.optInt("member_count"),
            unread = obj.optInt("unread"),
            peer = peer,
            last = last,
            archived = obj.optBoolean("archived"),
            pinned = obj.optBoolean("pinned"),
            mutedUntil = obj.optLong("muted_until"),
            forum = obj.optBoolean("forum"),
            noForwards = obj.optBoolean("no_forwards"),
        )
    }

    private fun personOf(obj: JSONObject) = Person(
        id = obj.optLong("id"),
        username = obj.optString("username"),
        displayName = obj.optString("display_name"),
        online = obj.optBoolean("online"),
        role = obj.optString("role"),
    )

    private fun messageOf(obj: JSONObject): ChatMessage {
        val reactions = obj.optJSONArray("reactions")
        val reactionText = buildString {
            if (reactions != null) {
                for (i in 0 until reactions.length()) {
                    val row = reactions.optJSONObject(i) ?: continue
                    if (row.optInt("count") <= 0) continue
                    if (isNotEmpty()) append(' ')
                    append(row.optString("emoji"))
                    append(row.optInt("count"))
                }
            }
        }
        return ChatMessage(
            id = obj.optLong("id"),
            dialogId = obj.optLong("dialog_id"),
            senderId = obj.optLong("sender_id"),
            text = obj.optString("text"),
            date = obj.optLong("date"),
            kind = obj.optString("kind", "text"),
            replyTo = obj.optLong("reply_to"),
            quote = obj.optString("quote"),
            fileId = obj.optLong("file_id"),
            silent = obj.optBoolean("silent"),
            editedAt = obj.optLong("edited_at"),
            entities = obj.optString("entities"),
            views = obj.optInt("views"),
            expiresAt = obj.optLong("expires_at"),
            topicId = obj.optLong("topic_id"),
            noForward = obj.optBoolean("no_forward"),
            transcript = obj.optString("transcript"),
            translation = obj.optString("translation"),
            reactions = reactionText,
        )
    }

    private fun Dialog.toRow(): DialogRow {
        val group = type == "group" || type == "channel"
        val title = when (type) {
            "group", "channel" -> this.title.ifBlank { if (type == "channel") "Канал" else "Группа" }
            "saved" -> "Избранное"
            "secret" -> "Секрет · " + (peer?.displayName?.ifBlank { peer?.username }.orEmpty().ifBlank { "чат" })
            else -> peer?.displayName?.ifBlank { peer?.username }.orEmpty().ifBlank { "Чат" }
        }
        val last = last
        val body = if (last == null) "" else previewOf(id, last.text).replace('\n', ' ')
        val preview = when {
            last == null -> "Нет сообщений"
            last.senderId == me?.id -> "Вы: $body"
            group -> {
                val name = userName(last.senderId)
                if (name.isBlank()) body else "$name: $body"
            }
            else -> body
        }
        return DialogRow(
            id = id,
            title = title,
            preview = preview,
            time = if (last == null) "" else listTime(last.date),
            unread = unread,
            online = !group && peer?.online == true,
            group = group,
            seed = peer?.id ?: id,
            type = type,
            archived = archived,
            pinned = pinned,
            muted = mutedUntil == -1L || (mutedUntil > 0 && mutedUntil > System.currentTimeMillis() / 1000),
        )
    }

    private fun JSONArray?.toPeople(): List<Person> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) add(personOf(getJSONObject(i)))
        }
    }

    private fun publishChat(dialogId: Long, stick: Boolean) {
        if (dialogId != activeDialogId) return
        val lines = linesFor(dialogId)
        post { callback.onChat(dialogId, lines, stick) }
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private data class Pending(val method: String, val deadline: Long, val callback: (JSONObject) -> Unit)
    private data class Dialog(
        val id: Long,
        val type: String,
        val title: String,
        val memberCount: Int,
        val unread: Int,
        val peer: Person?,
        val last: ChatMessage?,
        val archived: Boolean = false,
        val pinned: Boolean = false,
        val mutedUntil: Long = 0,
        val forum: Boolean = false,
        val noForwards: Boolean = false,
    )
    private enum class Queued { None, SignIn, SignUp }

    private inner class Framer {
        private val buf = ByteArrayOutputStream()
        fun push(data: ByteArray, len: Int): List<ByteArray> {
            buf.write(data, 0, len)
            val all = buf.toByteArray()
            if (all.size > 2 * 1024 * 1024) throw java.io.IOException("buffer")
            var i = 0
            val frames = mutableListOf<ByteArray>()
            while (all.size - i >= 4) {
                val raw = ByteBuffer.wrap(all, i, 4).int
                val packed = raw and 0x80000000.toInt() != 0
                val size = raw and 0x7fffffff
                if (size <= 0 || size > 1024 * 1024) throw java.io.IOException("frame")
                if (all.size - i - 4 < size) break
                var frame = all.copyOfRange(i + 4, i + 4 + size)
                if (packed) {
                    frame = inflate(frame)
                    if (frame.isEmpty()) throw java.io.IOException("inflate")
                }
                frames += frame
                i += 4 + size
            }
            buf.reset()
            if (i < all.size) buf.write(all, i, all.size - i)
            return frames
        }
    }

    fun editMessage(messageId: Long, text: String) = rpc("messages.edit", JSONObject().put("message_id", messageId).put("text", text)) { replace(messageOf(it)) }
    fun deleteMessage(messageId: Long) = rpc("messages.delete", JSONObject().put("message_id", messageId)) { erase(it.optLong("dialog_id"), it.optLong("message_id")) }
    fun react(messageId: Long, emoji: String) = rpc("messages.react", JSONObject().put("message_id", messageId).put("emoji", emoji)) { applyReaction(it) }
    fun forward(messageId: Long, dialogId: Long) = rpc("messages.forward", JSONObject().put("message_id", messageId).put("dialog_id", dialogId)) { store(messageOf(it)); publishDialogs() }
    fun forwardToSaved(messageId: Long) = rpc("dialogs.getSaved", JSONObject()) { forward(messageId, it.optLong("id")) }
    fun openSaved() = rpc("dialogs.getSaved", JSONObject()) { val dialog = upsert(it); openDialog(dialog.id) }
    fun createSecret(userId: Long, ttl: Int) = rpc("dialogs.createSecret", JSONObject().put("user_id", userId).put("ttl_seconds", ttl)) { val dialog = upsert(it); openDialog(dialog.id) }
    fun createChannel(title: String) = rpc("dialogs.createChannel", JSONObject().put("title", title)) { val dialog = upsert(it); openDialog(dialog.id) }
    fun createForum(title: String) {
        val params = JSONObject().put("title", title.trim()).put("member_ids", org.json.JSONArray()).put("forum", true)
        call("dialogs.createGroup", params) { response ->
            if (!response.optBoolean("ok")) post { callback.onNotice(humanize(errorCode(response))) }
            else openDialog(upsert(response.getJSONObject("result")).id)
        }
    }
    fun joinChannel(dialogId: Long) = rpc("dialogs.join", JSONObject().put("dialog_id", dialogId)) { openDialog(upsert(it).id) }
    fun leaveDialog() = rpc("dialogs.leave", JSONObject().put("dialog_id", activeDialogId)) { requestDialogs() }
    fun archiveDialog(on: Boolean) = rpc("dialogs.archive", JSONObject().put("dialog_id", activeDialogId).put("archived", on)) { upsert(it) }
    fun pinDialog(on: Boolean) = rpc("dialogs.pin", JSONObject().put("dialog_id", activeDialogId).put("pinned", on)) { upsert(it) }
    fun muteForever() = rpc("dialogs.mute", JSONObject().put("dialog_id", activeDialogId).put("until", -1)) { upsert(it) }
    fun createTopic(title: String) = rpc("dialogs.createTopic", JSONObject().put("dialog_id", activeDialogId).put("title", title)) { post { callback.onNotice("Тема создана") } }
    fun createFolder(title: String) = rpc("folders.create", JSONObject().put("title", title)) { post { callback.onNotice("Папка создана") } }
    fun postStory(caption: String, hours: Int, privacy: String, noShots: Boolean) = rpc("stories.post", JSONObject().put("caption", caption).put("hours", hours).put("privacy", privacy).put("no_screenshots", noShots)) { post { callback.onNotice("История опубликована") } }
    fun loadStories() = rpc("stories.get", JSONObject()) {
        val rows = mutableListOf<String>()
        val arr = it.optJSONArray("stories")
        if (arr != null) for (i in 0 until arr.length()) rows += arr.getJSONObject(i).optString("caption")
        post { callback.onBoard("Истории", rows) }
    }
    fun loadSessions() = rpc("account.sessions", JSONObject()) {
        val rows = mutableListOf<String>()
        val arr = it.optJSONArray("sessions")
        if (arr != null) for (i in 0 until arr.length()) {
            val row = arr.getJSONObject(i)
            rows += "${row.optString("device")} · ${row.optString("platform")}" + if (row.optBoolean("current")) " · это устройство" else " · ${row.optLong("id")}"
        }
        post { callback.onBoard("Сеансы", rows) }
    }
    fun terminateOthers() = rpc("account.terminateOthers", JSONObject()) { loadSessions() }
    fun terminateSession(sessionId: Long) = rpc("account.terminateSession", JSONObject().put("session_id", sessionId)) { loadSessions() }
    fun submitCloudPassword(challenge: String, password: String) {
        val params = JSONObject().put("challenge", challenge).put("password", password).put("device", android.os.Build.MODEL.take(64)).put("device_id", deviceId()).put("platform", "android")
        call("auth.checkCloudPassword", params) { response ->
            if (!response.optBoolean("ok")) post { callback.onAuthError(humanize(errorCode(response))) }
            else {
                val result = response.getJSONObject("result")
                applySession(personOf(result.getJSONObject("user")), result.optString("token"))
            }
        }
    }
    fun setCloudPassword(password: String, current: String) {
        val params = JSONObject().put("password", password)
        if (current.isNotEmpty()) params.put("current", current)
        rpc("account.setCloudPassword", params) { post { callback.onNotice("Облачный пароль включён") } }
    }
    fun setPrivacy(phone: String, seen: String, photo: String) = rpc("users.setPrivacy", JSONObject().put("phone", phone).put("last_seen", seen).put("photo", photo)) { post { callback.onNotice("Приватность сохранена") } }
    fun setPin(pin: String) {
        val edit = prefs.edit()
        if (pin.isEmpty()) edit.remove("pin") else edit.putString("pin", sha(pin))
        edit.apply()
        rpc("account.setLocalLock", JSONObject().put("kind", if (pin.isEmpty()) "none" else "pin")) {}
    }
    fun pinSet() = prefs.contains("pin")
    fun checkPin(pin: String) = prefs.getString("pin", "") == sha(pin)
    fun savePreferences(json: String) = rpc("account.setPreferences", JSONObject().put("preferences", json)) { post { callback.onNotice("Оформление сохранено") } }
    fun startCall() = rpc("calls.start", JSONObject().put("dialog_id", activeDialogId)) { post { callback.onBoard("Эфир", listOf("id ${it.optLong("id")}")) } }
    fun callState(callId: Long, muted: Boolean, video: Boolean, screen: Boolean) = rpc("calls.setState", JSONObject().put("call_id", callId).put("muted", muted).put("video", video).put("screen", screen)) {}
    fun leaveCall(callId: Long) = rpc("calls.leave", JSONObject().put("call_id", callId)) {}
    fun recordCall(callId: Long) {
        rpc("calls.record", JSONObject().put("call_id", callId).put("recording", true)) {
            io.execute {
                try {
                    val rate = 16000
                    val min = android.media.AudioRecord.getMinBufferSize(
                        rate,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                    )
                    val rec = android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.MIC,
                        rate,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        min.coerceAtLeast(4096),
                    )
                    val buf = ByteArray(min.coerceIn(4096, 64 * 1024))
                    rec.startRecording()
                    val n = rec.read(buf, 0, buf.size)
                    rec.stop()
                    rec.release()
                    if (n > 0) {
                        rpc(
                            "calls.media",
                            JSONObject().put("call_id", callId).put("kind", "audio")
                                .put("data", Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP)),
                        ) { post { callback.onNotice("Фрагмент эфира отправлен") } }
                    }
                } catch (_: Exception) {
                    post { callback.onNotice("Нет доступа к микрофону") }
                }
            }
        }
    }

    fun saveCall(callId: Long) = rpc("calls.record", JSONObject().put("call_id", callId).put("recording", false)) {
        post { callback.onNotice("Запись эфира в Избранном") }
    }
    fun createBot(username: String, name: String) = rpc("bots.create", JSONObject().put("username", username).put("display_name", name)) { post { callback.onBoard("Бот", listOf(it.optString("token"))) } }
    fun botSignIn(token: String) = rpc("auth.botSignIn", JSONObject().put("token", token)) { val result = it; applySession(personOf(result.getJSONObject("user")), result.optString("token")) }
    fun loadApps() = rpc("apps.list", JSONObject()) {
        val rows = mutableListOf<String>()
        val arr = it.optJSONArray("apps")
        if (arr != null) for (i in 0 until arr.length()) rows += arr.getJSONObject(i).optString("title") + " " + arr.getJSONObject(i).optString("url")
        post { callback.onBoard("Приложения", rows) }
    }
    fun createApp(title: String, shortName: String, url: String) = rpc("apps.create", JSONObject().put("title", title).put("short_name", shortName).put("url", url)) { loadApps() }

    fun setAppContent(shortName: String, html: String) = rpc(
        "apps.setContent",
        JSONObject().put("short_name", shortName.trim()).put("html", html),
    ) { post { callback.onNotice("Страница мини-приложения сохранена") } }

    fun openMiniApp(shortName: String) {
        val url = "http://$host:7411/a/${shortName.trim()}"
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        view.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(view)
        } catch (_: Exception) {
            post { callback.onNotice(url) }
        }
    }

    fun editServerFile(fileId: Long, brightness: Int) = rpc(
        "files.edit",
        JSONObject().put("file_id", fileId).put("brightness", brightness).put("contrast", 0).put("rotate", 0),
    ) { post { callback.onNotice("Новый файл ${it.optLong("id")}") } }
    fun loadInvoices() = rpc("payments.list", JSONObject()) {
        val rows = mutableListOf<String>()
        val arr = it.optJSONArray("invoices")
        if (arr != null) for (i in 0 until arr.length()) rows += arr.getJSONObject(i).optString("title") + " " + arr.getJSONObject(i).optString("status")
        post { callback.onBoard("Счета", rows) }
    }
    fun createInvoice(userId: Long, title: String, payUrl: String = "") {
        val params = JSONObject().put("user_id", userId).put("title", title).put("amount", 100).put("currency", "RUB").put("purpose", "premium")
        if (payUrl.startsWith("http://") || payUrl.startsWith("https://")) params.put("pay_url", payUrl.take(512))
        rpc("payments.createInvoice", params) { post { callback.onNotice("Счёт ${it.optLong("id")}") } }
    }
    fun confirmInvoice(invoiceId: Long, ref: String) = rpc("payments.confirm", JSONObject().put("invoice_id", invoiceId).put("provider_ref", ref)) { post { callback.onNotice(it.optString("status")) } }
    fun loadStickers() = rpc("stickers.list", JSONObject()) {
        val rows = mutableListOf<String>()
        val arr = it.optJSONArray("packs")
        if (arr != null) for (i in 0 until arr.length()) rows += arr.getJSONObject(i).optString("title")
        post { callback.onBoard("Стикеры", rows) }
    }
    fun createSavedTab(title: String) = rpc("saved.createTab", JSONObject().put("title", title)) { post { callback.onNotice("Вкладка создана") } }
    fun tagSaved(messageId: Long, tag: String) = rpc("saved.tag", JSONObject().put("message_id", messageId).put("tag", tag)) { post { callback.onNotice("Тег сохранён") } }
    fun translate(messageId: Long, text: String) {
        val cyr = text.any { it.code in 0x0400..0x04FF }
        val pair = if (cyr) "ru|en" else "en|ru"
        Thread {
            try {
                val url = java.net.URL("https://api.mymemory.translated.net/get?q=" + java.net.URLEncoder.encode(text.take(450), "UTF-8") + "&langpair=" + pair)
                val payload = url.readText()
                val translated = org.json.JSONObject(payload).getJSONObject("responseData").optString("translatedText")
                if (translated.isBlank()) post { callback.onNotice("Переводчик не ответил") }
                else rpc("messages.setTranslation", JSONObject().put("message_id", messageId).put("lang", pair.substringAfter('|')).put("text", translated.take(4000))) {
                    post { callback.onNotice(translated) }
                }
            } catch (_: Exception) {
                post { callback.onNotice("Переводчик не ответил") }
            }
        }.start()
    }
    fun setTranscript(messageId: Long, text: String) = rpc("messages.setTranscript", JSONObject().put("message_id", messageId).put("text", text)) { post { callback.onNotice("Расшифровка сохранена") } }
    fun reportScreenshot() {
        val id = activeDialogId
        if (synchronized(state) { dialogs.firstOrNull { it.id == id }?.type } == "secret")
            rpc("messages.reportScreenshot", JSONObject().put("dialog_id", id)) {}
    }
    fun rememberAccount(label: String) {
        if (token.isEmpty()) return
        val arr = org.json.JSONArray(prefs.getString("accounts", "[]"))
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("token") == token) return
        if (arr.length() >= 4) {
            post { callback.onNotice("Лимит аккаунтов на устройстве") }
            return
        }
        arr.put(JSONObject().put("label", label.ifBlank { me?.username ?: "аккаунт" }).put("token", token).put("host", host).put("port", port))
        prefs.edit().putString("accounts", arr.toString()).apply()
        post { callback.onNotice("Аккаунт запомнен") }
    }
    fun accounts(): List<String> {
        val arr = org.json.JSONArray(prefs.getString("accounts", "[]"))
        return buildList { for (i in 0 until arr.length()) add(arr.getJSONObject(i).optString("label")) }
    }
    fun switchAccount(index: Int) {
        val arr = org.json.JSONArray(prefs.getString("accounts", "[]"))
        if (index !in 0 until arr.length()) return
        val row = arr.getJSONObject(index)
        token = row.optString("token")
        host = row.optString("host", host)
        port = row.optInt("port", port)
        prefs.edit().putString("token", token).putString("host", host).putInt("port", port).apply()
        clearChat()
        me = null
        closeSocket()
        post { callback.onSession(null, token.isNotEmpty()) }
    }

    private fun rpc(method: String, params: JSONObject, ok: (JSONObject) -> Unit) {
        call(method, params) { response ->
            if (!response.optBoolean("ok")) {
                val code = errorCode(response)
                if (code == "UNAUTHORIZED" || code == "SESSION_REVOKED") loseSession() else post { callback.onNotice(humanize(code)) }
                return@call
            }
            ok(response.optJSONObject("result") ?: JSONObject())
        }
    }

    private fun replace(message: ChatMessage) {
        synchronized(state) {
            val list = messages[message.dialogId].orEmpty().toMutableList()
            val index = list.indexOfFirst { it.id == message.id }
            if (index >= 0) list[index] = message else list += message
            messages[message.dialogId] = list
        }
        if (message.dialogId == activeDialogId) publishChat(message.dialogId, false)
    }

    private fun erase(dialogId: Long, messageId: Long) {
        synchronized(state) {
            messages[dialogId] = messages[dialogId].orEmpty().filter { it.id != messageId }.toMutableList()
        }
        if (dialogId == activeDialogId) publishChat(dialogId, false)
    }

    private fun applyReaction(data: JSONObject) {
        val dialogId = data.optLong("dialog_id")
        val messageId = data.optLong("message_id")
        val emoji = data.optString("emoji")
        val added = data.optBoolean("added")
        synchronized(state) {
            val list = messages[dialogId].orEmpty().toMutableList()
            val index = list.indexOfFirst { it.id == messageId }
            if (index >= 0) {
                val message = list[index]
                val mark = emoji + "1"
                val next = if (added) (message.reactions + " " + mark).trim() else message.reactions
                list[index] = message.copy(reactions = next)
                messages[dialogId] = list
            }
        }
        if (dialogId == activeDialogId) publishChat(dialogId, false)
    }

    private fun isMuted(dialogId: Long): Boolean {
        val until = dialogs.firstOrNull { it.id == dialogId }?.mutedUntil ?: return false
        return until == -1L || (until > 0 && until > System.currentTimeMillis() / 1000)
    }

    private fun previewOf(dialogId: Long, text: String): String {
        if (text.startsWith("{\"e\":1")) return openRk1(text)
        if (!text.startsWith("e2e:")) return text
        val key = aes[dialogId] ?: return "Секретное сообщение"
        return SecretBox.open(key, text).ifBlank { "Секретное сообщение" }
    }

    private fun present(message: ChatMessage): String {
        val body = previewOf(message.dialogId, message.text)
        val prefix = when (message.kind) {
            "voice" -> "Голосовое. "
            "video_note" -> "Видеосообщение. "
            "file" -> "Файл. "
            "photo" -> "Фото. "
            "sticker" -> "Стикер "
            "sponsor" -> "Реклама. "
            else -> ""
        }
        return buildString {
            if (message.quote.isNotBlank()) append("↩ ").append(message.quote).append('\n')
            append(prefix).append(body)
            if (message.editedAt > 0) append("\nизменено")
            if (message.translation.isNotBlank()) append("\nперевод: ").append(message.translation)
            if (message.transcript.isNotBlank()) append('\n').append(message.transcript)
            if (message.reactions.isNotBlank()) append('\n').append(message.reactions)
            if (message.views > 0) append("\nпросмотры: ").append(message.views)
            if (delivered.contains(message.id)) append("\nдоставлено")
        }
    }

    private fun deviceId(): String {
        var id = prefs.getString("device_id", "").orEmpty()
        if (id.length < 8 || id.length > 64) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun armSession() {
        rpc("session.setOptions", JSONObject().put("compress", true)) { compress = it.optBoolean("compress") }
        publishIdentity()
    }

    private fun publishIdentity() {
        if (!X25519.selfTest()) {
            post { callback.onNotice("Шифрование X25519 не прошло проверку") }
            return
        }
        if (xPriv.size != 32) {
            val fresh = ByteArray(32)
            SecureRandom().nextBytes(fresh)
            xPriv = fresh
            prefs.edit().putString("x25519", Base64.encodeToString(fresh, Base64.NO_WRAP)).apply()
        }
        val pub = Base64.encodeToString(X25519.base(xPriv), Base64.NO_WRAP)
        rpc("keys.publish", JSONObject().put("public_key", pub)) {}
    }

    private fun openRk1(text: String): String {
        return try {
            val root = JSONObject(text)
            if (root.optInt("e") != 1) return text
            val seals = root.optJSONArray("seals") ?: return "Секретное сообщение"
            val meId = me?.id ?: return "Секретное сообщение"
            if (xPriv.size != 32) return "Секретное сообщение"
            for (i in 0 until seals.length()) {
                val row = seals.getJSONObject(i)
                if (row.optLong("user_id") != meId) continue
                val parts = row.optString("box").split('.')
                if (parts.size != 4 || parts[0] != "rk1") return "Секретное сообщение"
                val epub = Base64.decode(parts[1], Base64.DEFAULT)
                val nonce = Base64.decode(parts[2], Base64.DEFAULT)
                val boxed = Base64.decode(parts[3], Base64.DEFAULT)
                val key = MessageDigest.getInstance("SHA-256").digest(X25519.scalarmult(xPriv, epub))
                return SecretBox.openGcm(key, nonce, boxed).ifBlank { "Секретное сообщение" }
            }
            "Секретное сообщение"
        } catch (_: Exception) {
            "Секретное сообщение"
        }
    }

    private fun playCallAudio(data: JSONObject) {
        if (data.optString("kind") != "audio") {
            post { callback.onNotice("Фрагмент эфира") }
            return
        }
        val raw = try {
            Base64.decode(data.optString("data"), Base64.DEFAULT)
        } catch (_: Exception) {
            ByteArray(0)
        }
        if (raw.size < 32) {
            post { callback.onNotice("Фрагмент эфира") }
            return
        }
        try {
            val track = android.media.AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                16000,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                raw.size,
                android.media.AudioTrack.MODE_STATIC,
            )
            track.write(raw, 0, raw.size)
            track.play()
        } catch (_: Exception) {
            post { callback.onNotice("Фрагмент эфира") }
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > 1024 * 1024) break
            }
            out.toByteArray()
        } catch (_: Exception) {
            ByteArray(0)
        } finally {
            inflater.end()
        }
    }

    private fun ensureSecret(dialogId: Long) {
        val privKey = "sec-priv-$dialogId"
        val pubKey = "sec-pub-$dialogId"
        var priv = prefs.getString(privKey, "").orEmpty()
        var pub = prefs.getString(pubKey, "").orEmpty()
        if (priv.isBlank() || pub.isBlank()) {
            val identity = SecretBox.generate()
            priv = identity.privatePkcs8
            pub = identity.publicRaw
            prefs.edit().putString(privKey, priv).putString(pubKey, pub).apply()
        }
        rpc("dialogs.setSecretKey", JSONObject().put("dialog_id", dialogId).put("public_key", pub)) {
            rpc("dialogs.secretKeys", JSONObject().put("dialog_id", dialogId)) { result ->
                val keys = result.optJSONArray("keys") ?: return@rpc
                for (i in 0 until keys.length()) {
                    val row = keys.getJSONObject(i)
                    if (row.optLong("user_id") == me?.id) continue
                    val agreed = SecretBox.agree(priv, row.optString("public_key"))
                    if (agreed.isNotEmpty()) aes[dialogId] = agreed
                }
                if (dialogId == activeDialogId) publishChat(dialogId, false)
            }
        }
    }

    private fun sha(pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.222.2"
        const val DEFAULT_PORT = 7410
        private const val PAGE = 50
        private val months = arrayOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря",
        )

        private fun errorCode(response: JSONObject) =
            response.optJSONObject("error")?.optString("message").orEmpty()

        private fun humanize(code: String) = when (code) {
            "BAD_USERNAME" -> "Имя: 3–32 символа, латиница, цифры и _"
            "BAD_PASSWORD" -> "Пароль: от 8 до 128 символов"
            "BAD_DISPLAY_NAME" -> "Имя на экране: 1–64 символа"
            "USERNAME_TAKEN" -> "Такое имя уже занято"
            "INVALID_CREDENTIALS" -> "Неверное имя или пароль"
            "INVALID_TOKEN" -> "Сессия недействительна"
            "UNAUTHORIZED" -> "Нужно войти"
            "NOT_CONNECTED" -> "Нет соединения"
            "TIMEOUT" -> "Сервер не ответил"
            "USER_NOT_FOUND" -> "Пользователь не найден"
            "NOT_MEMBER" -> "Вы не в этом чате"
            "NOT_OWNER" -> "Это может сделать только владелец"
            "BAD_TEXT" -> "Пустое или слишком длинное сообщение"
            "BAD_TITLE" -> "Неверное название группы"
            "TOO_MANY_MEMBERS" -> "В группе больше 200000 участников"
            "READ_ONLY" -> "В этом чате нельзя писать"
            "NO_FORWARD" -> "Пересылка запрещена"
            "PREMIUM_REQUIRED" -> "Нужна подписка Premium"
            "NOT_SECRET" -> "Это не секретный чат"
            "NOT_A_CHANNEL" -> "Это не канал"
            "FILE_REQUIRED" -> "Нужен файл"
            "BAD_SIZE" -> "Неверный размер файла"
            "TOO_MANY_PINS" -> "Слишком много закреплённых чатов"
            "TOO_MANY_FOLDERS" -> "Слишком много папок"
            "SESSION_REVOKED" -> "Сеанс завершён"
            "PAYMENT_DETAILS_REJECTED" -> "Номер карты и ключ кошелька сервер не принимает"
            "NOT_A_BOT" -> "Это может сделать только бот"
            "BAD_PEER" -> "Нельзя написать самому себе"
            "BAD_CONTACT" -> "Себя добавить нельзя"
            else -> if (code.isBlank()) "Неизвестная ошибка" else code
        }

        private fun validateUsername(name: String): String? =
            if (name.matches(Regex("^[A-Za-z0-9_]{3,32}$"))) null
            else "Имя: 3–32 символа, латиница, цифры и _"

        private fun validatePassword(password: String): String? {
            val size = password.toByteArray(Charsets.UTF_8).size
            return if (size in 8..128) null else "Пароль: от 8 до 128 символов"
        }

        private fun validateDisplay(name: String): String? {
            val size = name.toByteArray(Charsets.UTF_8).size
            return if (name.isNotEmpty() && size <= 64 && !hasControls(name, false)) null
            else "Имя на экране: 1–64 символа"
        }

        private fun hasControls(text: String, allowBreaks: Boolean): Boolean {
            for (ch in text) {
                if (ch.code >= 0x20) continue
                if (allowBreaks && (ch == '\n' || ch == '\t')) continue
                return true
            }
            return false
        }

        private fun calendar(unix: Long): Calendar =
            Calendar.getInstance().apply { timeInMillis = unix * 1000 }

        private fun dayKey(unix: Long): Int {
            val c = calendar(unix)
            return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        }

        private fun clock(unix: Long): String {
            val c = calendar(unix)
            return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }

        private fun listTime(unix: Long): String {
            val c = calendar(unix)
            val today = Calendar.getInstance()
            if (sameDay(c, today)) return clock(unix)
            val y = today.clone() as Calendar
            y.add(Calendar.DAY_OF_YEAR, -1)
            if (sameDay(c, y)) return "вчера"
            val day = "%02d.%02d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
            return if (c.get(Calendar.YEAR) == today.get(Calendar.YEAR)) day
            else day + "." + (c.get(Calendar.YEAR) % 100).toString().padStart(2, '0')
        }

        private fun dayLabel(unix: Long): String {
            val c = calendar(unix)
            val today = Calendar.getInstance()
            if (sameDay(c, today)) return "Сегодня"
            val y = today.clone() as Calendar
            y.add(Calendar.DAY_OF_YEAR, -1)
            if (sameDay(c, y)) return "Вчера"
            val text = "${c.get(Calendar.DAY_OF_MONTH)} ${months[c.get(Calendar.MONTH)]}"
            return if (c.get(Calendar.YEAR) == today.get(Calendar.YEAR)) text
            else "$text ${c.get(Calendar.YEAR)}"
        }

        private fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}
