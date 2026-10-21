package id.rock.messenger

import android.graphics.Typeface
import android.os.Build
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity(), RockClient.Callback {
    private lateinit var client: RockClient
    private lateinit var content: FrameLayout
    private var screen = Screen.LOGIN
    private var me: Person? = null
    private var dialogRows = listOf<DialogRow>()
    private var chatLines = listOf<ChatLine>()
    private var people = listOf<Person>()
    private var members = listOf<Person>()
    private var myRole = "member"
    private var picked = mutableSetOf<Long>()
    private var linkText = "Подключение…"
    private var linkOnline = false
    private var signup = false
    private var cloudChallenge = ""
    private var unlocked = false
    private var boardTitle = ""
    private var boardLines = listOf<String>()

    private var linkView: TextView? = null
    private var authErrorView: TextView? = null
    private var dialogAdapter: DialogAdapter? = null
    private var dialogList: RecyclerView? = null
    private var dialogEmpty: TextView? = null
    private var chatAdapter: MessageAdapter? = null
    private var chatList: RecyclerView? = null
    private var peopleAdapter: PersonAdapter? = null
    private var memberAdapter: PersonAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = FrameLayout(this).apply {
            setBackgroundColor(C_WINDOW)
            fitsSystemWindows = true
        }
        setContentView(content)
        client = RockClient(this, this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        show(if (client.savedSession()) Screen.RESTORE else Screen.LOGIN)
        client.start()
        if (Build.VERSION.SDK_INT >= 34) {
            registerScreenCaptureCallback(mainExecutor) { client.reportScreenshot() }
        }
    }

    override fun onDestroy() {
        client.stop()
        super.onDestroy()
    }

    override fun onLink(online: Boolean, text: String) {
        linkOnline = online
        linkText = text
        linkView?.text = text
        linkView?.setTextColor(if (online) C_ONLINE else C_MUTED)
    }

    override fun onSession(person: Person?, restoring: Boolean) {
        me = person
        when {
            person != null && (screen == Screen.LOGIN || screen == Screen.RESTORE) ->
                show(if (client.pinSet() && !unlocked) Screen.LOCK else Screen.DIALOGS)
            person == null && restoring && screen != Screen.RESTORE -> show(Screen.RESTORE)
            person == null && !restoring && screen != Screen.LOGIN -> show(Screen.LOGIN)
        }
    }

    override fun onAuthError(text: String) {
        val view = authErrorView
        if (view != null) {
            view.text = text
            view.visibility = View.VISIBLE
        } else {
            toast(text)
        }
    }

    override fun onDialogs(rows: List<DialogRow>) {
        dialogRows = rows
        dialogAdapter?.submit(rows)
        dialogEmpty?.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        dialogList?.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onChat(dialogId: Long, lines: List<ChatLine>, stickToEnd: Boolean) {
        if (dialogId != client.activeDialogId) return
        chatLines = lines
        if (screen == Screen.PEOPLE || screen == Screen.GROUP) {
            show(Screen.CHAT)
            return
        }
        val adapter = chatAdapter ?: return
        val list = chatList ?: return
        val atEnd = !list.canScrollVertically(1)
        adapter.submit(lines)
        if ((stickToEnd || atEnd) && adapter.itemCount > 0) list.scrollToPosition(adapter.itemCount - 1)
    }

    override fun onPeople(rows: List<Person>, contacts: Boolean) {
        if (screen == Screen.MEMBERS && contacts) return
        people = rows
        peopleAdapter?.submit(rows)
        memberAdapter?.submit(if (screen == Screen.MEMBERS) rows else emptyList())
    }

    override fun onMembers(rows: List<Person>, role: String) {
        members = rows
        myRole = role
        if (screen == Screen.MEMBERS) show(Screen.MEMBERS)
    }

    override fun onNotice(text: String) = toast(text)

    override fun onCloudPassword(challenge: String) {
        cloudChallenge = challenge
        toast("Нужен облачный пароль")
        show(Screen.LOGIN)
    }

    override fun onBoard(title: String, rows: List<String>) {
        boardTitle = title
        boardLines = rows
        if (screen == Screen.TOOLS) show(Screen.TOOLS) else toast(rows.firstOrNull() ?: title)
    }

    private fun show(next: Screen) {
        screen = next
        linkView = null
        authErrorView = null
        dialogAdapter = null
        dialogList = null
        dialogEmpty = null
        chatAdapter = null
        chatList = null
        peopleAdapter = null
        memberAdapter = null
        content.removeAllViews()
        when (next) {
            Screen.LOGIN -> login(restoring = false)
            Screen.RESTORE -> login(restoring = true)
            Screen.DIALOGS -> dialogs()
            Screen.CHAT -> chat()
            Screen.PEOPLE -> showPeople(group = false)
            Screen.GROUP -> showPeople(group = true)
            Screen.MEMBERS -> showMembers()
            Screen.TOOLS -> showTools()
            Screen.LOCK -> showLock()
        }
    }

    private fun login(restoring: Boolean) {
        val page = scroll(column(24))
        content.addView(page, match())
        val column = (page.getChildAt(0) as LinearLayout)
        column.addView(logo())
        column.addView(title(if (restoring) "Входим" else if (signup) "Создать аккаунт" else "Войти"))
        linkView = label(linkText, 13f, if (linkOnline) C_ONLINE else C_MUTED)
        column.addView(linkView)
        if (restoring) {
            column.addView(space(12))
            column.addView(button("Войти другим аккаунтом") { client.forgetSession() })
            return
        }
        column.addView(label(if (signup) "Имя латиницей, пароль от 8 символов." else "Имя и пароль от сервера rockID.", 13f, C_MUTED))
        val username = field("username")
        val display = field("Имя на экране")
        val password = field("Пароль", password = true)
        val again = field("Пароль ещё раз", password = true)
        display.visibility = if (signup) View.VISIBLE else View.GONE
        again.visibility = if (signup) View.VISIBLE else View.GONE
        column.addView(username)
        column.addView(display)
        column.addView(password)
        column.addView(again)
        authErrorView = label("", 13f, C_DANGER).apply { visibility = View.GONE }
        column.addView(authErrorView)
        val host = field("192.168.222.2").apply { setText(client.host) }
        val port = field("7410").apply {
            setText(client.port.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        column.addView(host)
        column.addView(port)
        column.addView(button(if (signup) "Зарегистрироваться" else "Войти") {
            val portNumber = port.text.toString().toIntOrNull() ?: RockClient.DEFAULT_PORT
            client.setEndpoint(host.text.toString(), portNumber)
            if (cloudChallenge.isNotEmpty()) {
                client.submitCloudPassword(cloudChallenge, password.text.toString())
                cloudChallenge = ""
                return@button
            }
            if (signup) {
                if (password.text.toString() != again.text.toString()) {
                    onAuthError("Пароли не совпадают")
                    return@button
                }
                client.signUp(username.text.toString(), password.text.toString(), display.text.toString())
            } else {
                client.signIn(username.text.toString(), password.text.toString())
            }
        })
        column.addView(textButton(if (signup) "Уже есть аккаунт" else "Создать аккаунт") {
            signup = !signup
            show(Screen.LOGIN)
        })
        column.addView(label("Сервер ${client.host}:${client.port}", 12f, C_MUTED))
    }

    private fun dialogs() {
        val page = column(0)
        content.addView(page, match())
        page.addView(bar("rockID", "Новый") { show(Screen.PEOPLE) })
        val list = recycler()
        val adapter = DialogAdapter { openChat(it.id) }
        dialogAdapter = adapter
        dialogList = list
        adapter.submit(dialogRows)
        list.adapter = adapter
        page.addView(list, LinearLayout.LayoutParams(matchW(), 0, 1f))
        val empty = label("Чатов пока нет", 14f, C_MUTED).apply { gravity = Gravity.CENTER }
        dialogEmpty = empty
        empty.visibility = if (dialogRows.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (dialogRows.isEmpty()) View.GONE else View.VISIBLE
        page.addView(empty, LinearLayout.LayoutParams(matchW(), 0, 1f))
        val me = this.me
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C_HEADER)
            for (mode in listOf("Все", "Непрочитанные", "Архив")) {
                addView(TextView(this@MainActivity).apply {
                    text = mode
                    setTextColor(if (client.listMode == mode) C_ACCENT else C_MUTED)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener { client.chooseList(mode); show(Screen.DIALOGS) }
                })
            }
        }
        page.addView(modes, 1)
        page.addView(bar(me?.displayName ?: "", "Ещё") { show(Screen.TOOLS) }.apply {
            setBackgroundColor(C_HEADER)
        })
    }

    private fun chat() {
        val dialog = dialogRows.firstOrNull { it.id == client.activeDialogId }
        if (dialog?.type == "secret") window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val page = column(0)
        content.addView(page, match())
        val header = bar(dialog?.title ?: "Чат", if (dialog?.group == true) "Ещё" else "Назад") {
            if (dialog?.group == true) {
                client.loadMembers()
                show(Screen.MEMBERS)
            } else {
                show(Screen.DIALOGS)
            }
        }
        page.addView(header)
        val list = recycler()
        chatList = list
        val adapter = MessageAdapter({ client.loadOlder() }, { messageMenu(it) })
        chatAdapter = adapter
        adapter.submit(chatLines)
        list.adapter = adapter
        page.addView(list, LinearLayout.LayoutParams(matchW(), 0, 1f))
        if (adapter.itemCount > 0) list.scrollToPosition(adapter.itemCount - 1)
        val composer = EditText(this).apply {
            hint = "Сообщение"
            setHintTextColor(C_MUTED)
            setTextColor(C_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = round(C_INPUT, 18)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val send = TextView(this).apply {
            text = "➤"
            setTextColor(C_TEXT)
            gravity = Gravity.CENTER
            background = circle(C_ACCENT)
            setOnClickListener { send(composer) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(C_HEADER)
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "⋯"
                setTextColor(C_ACCENT)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { show(Screen.TOOLS) }
            })
            addView(composer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) })
        }
        page.addView(row)
        if (dialog?.group != true) {
            header.setOnClickListener { show(Screen.DIALOGS) }
        }
    }

    private fun showPeople(group: Boolean) {
        val page = column(0)
        content.addView(page, match())
        page.addView(bar(if (group) "Новая группа" else "Новый чат", "Назад") {
            show(Screen.DIALOGS)
        })
        val title = if (group) field("Название группы") else null
        if (title != null) {
            title.setPadding(dp(16), dp(8), dp(16), dp(8))
            page.addView(pad(title, 12, 8, 12, 0))
        }
        val query = field(if (group) "Найти участников" else "username")
        query.setPadding(dp(16), dp(8), dp(16), dp(8))
        query.addTextChangedListener(SimpleText { client.search(query.text.toString()) })
        page.addView(pad(query, 12, 8, 12, 8))
        val list = recycler()
        val adapter = PersonAdapter(group, picked, { person ->
            if (group) {
                if (!picked.add(person.id)) picked.remove(person.id)
                peopleAdapter?.notifyDataSetChanged()
            } else {
                client.startPrivate(person.id)
            }
        }, { client.addContact(it.id) })
        peopleAdapter = adapter
        adapter.submit(people)
        list.adapter = adapter
        page.addView(list, LinearLayout.LayoutParams(matchW(), 0, 1f))
        if (!group) {
            page.addView(button("Новая группа") {
                picked.clear()
                show(Screen.GROUP)
            }.let { pad(it, 12, 0, 12, 12) })
        } else {
            page.addView(button("Создать") {
                client.createGroup(title?.text?.toString().orEmpty(), picked.toList())
            }.let { pad(it, 12, 0, 12, 12) })
        }
        if (people.isEmpty()) client.search("")
    }

    private fun showMembers() {
        val page = column(0)
        content.addView(page, match())
        page.addView(bar("Участники", "Назад") { show(Screen.CHAT) })
        val list = recycler()
        val adapter = PersonAdapter(checkable = false, picked = mutableSetOf(), onClick = {}, onContact = {})
        list.adapter = adapter
        adapter.submit(members)
        page.addView(list, LinearLayout.LayoutParams(matchW(), 0, 1f))
        if (myRole == "owner") {
            val query = field("Добавить по username")
            query.addTextChangedListener(SimpleText { client.search(query.text.toString()) })
            page.addView(pad(query, 12, 8, 12, 8))
            val found = recycler()
            val foundAdapter = PersonAdapter(false, mutableSetOf(), { client.addMember(it.id) }, {})
            memberAdapter = foundAdapter
            found.adapter = foundAdapter
            page.addView(found, LinearLayout.LayoutParams(matchW(), dp(180)))
        }
    }

    private fun openChat(id: Long) {
        chatLines = emptyList()
        show(Screen.CHAT)
        client.openDialog(id)
    }

    private fun send(input: EditText) {
        if (client.sendMessage(input.text.toString())) input.setText("")
    }

    private fun goBack() {
        when (screen) {
            Screen.CHAT, Screen.PEOPLE, Screen.GROUP, Screen.TOOLS -> show(if (client.activeDialogId > 0 && screen == Screen.TOOLS) Screen.CHAT else Screen.DIALOGS)
            Screen.MEMBERS -> show(Screen.CHAT)
            Screen.LOCK -> finish()
            Screen.RESTORE -> client.forgetSession()
            else -> finish()
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun column(pad: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C_WINDOW)
        if (pad > 0) setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun scroll(child: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(child, FrameLayout.LayoutParams(matchW(), FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun logo(): TextView = TextView(this).apply {
        text = "r"
        gravity = Gravity.CENTER
        setTextColor(C_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        typeface = Typeface.DEFAULT_BOLD
        background = round(C_ACCENT, 18)
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(16) }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(C_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(8))
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun field(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(C_MUTED)
        setTextColor(C_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = round(C_INPUT, 12)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(matchW(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    private fun button(text: String, click: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(C_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        background = round(C_ACCENT, 12)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(matchW(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
    }

    private fun textButton(text: String, click: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(C_ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(8), dp(14), dp(8), dp(8))
        setOnClickListener { click() }
    }

    private fun bar(title: String, action: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(C_HEADER)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(8), dp(12))
        addView(TextView(context).apply {
            text = title
            setTextColor(C_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = action
            setTextColor(C_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { click() }
        })
    }

    private fun recycler() = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@MainActivity)
        setBackgroundColor(C_WINDOW)
    }

    private fun pad(view: View, l: Int, t: Int, r: Int, b: Int): View = LinearLayout(this).apply {
        setPadding(dp(l), dp(t), dp(r), dp(b))
        addView(view, LinearLayout.LayoutParams(matchW(), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun space(v: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(matchW(), dp(v))
    }

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun match() = FrameLayout.LayoutParams(matchW(), FrameLayout.LayoutParams.MATCH_PARENT)
    private fun matchW() = LinearLayout.LayoutParams.MATCH_PARENT

    private enum class Screen { LOGIN, RESTORE, DIALOGS, CHAT, PEOPLE, GROUP, MEMBERS, TOOLS, LOCK }

    private fun showLock() {
        val page = column(24)
        content.addView(page, match())
        page.addView(title("Код-пароль"))
        val pin = field("Пин-код", password = true)
        page.addView(pin)
        page.addView(button("Открыть") {
            if (client.checkPin(pin.text.toString())) {
                unlocked = true
                show(Screen.DIALOGS)
            } else toast("Неверный код")
        })
    }

    private fun messageMenu(row: ChatLine.Bubble) {
        val actions = arrayOf("Ответить", "Изменить", "Удалить", "👍", "Перевести", "В Избранное", "Расшифровка")
        AlertDialog.Builder(this)
            .setTitle(row.text.take(80))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> toast("Ответ отправится следующей строкой") 
                    1 -> prompt("Новый текст") { client.editMessage(row.id, it) }
                    2 -> client.deleteMessage(row.id)
                    3 -> client.react(row.id, "👍")
                    4 -> client.translate(row.id, row.text)
                    5 -> client.forwardToSaved(row.id)
                    6 -> prompt("Текст расшифровки") { client.setTranscript(row.id, it) }
                }
            }
            .show()
    }

    private fun prompt(hint: String, ok: (String) -> Unit) {
        val input = field(hint)
        AlertDialog.Builder(this).setTitle(hint).setView(input).setPositiveButton("Готово") { _, _ -> ok(input.text.toString()) }.show()
    }

    private fun showTools() {
        val page = column(0)
        content.addView(page, match())
        page.addView(bar(boardTitle.ifBlank { "Действия" }, "Назад") {
            show(if (client.activeDialogId > 0) Screen.CHAT else Screen.DIALOGS)
        })
        val body = scroll(column(12))
        page.addView(body, LinearLayout.LayoutParams(matchW(), 0, 1f))
        val column = body.getChildAt(0) as LinearLayout
        val extra = field("Текст, id или подпись")
        val second = field("Второе поле")
        column.addView(extra)
        column.addView(second)
        fun act(title: String, run: () -> Unit) = column.addView(button(title, run).let { pad(it, 0, 4, 0, 4) })
        act("Избранное") { client.openSaved() }
        act("Секретный чат") { client.createSecret(extra.text.toString().toLongOrNull() ?: 0L, 3600) }
        act("Канал") { client.createChannel(extra.text.toString()) }
        act("Форум") { client.createForum(extra.text.toString()) }
        act("В канал") { client.joinChannel(extra.text.toString().toLongOrNull() ?: 0L) }
        act("Тема") { client.createTopic(extra.text.toString()) }
        act("Архив") { client.archiveDialog(true) }
        act("Закрепить") { client.pinDialog(true) }
        act("Без звука навсегда") { client.muteForever() }
        act("Выйти из чата") { client.leaveDialog() }
        act("Папка") { client.createFolder(extra.text.toString()) }
        act("История 24 ч") { client.postStory(extra.text.toString(), 24, "everybody", true) }
        act("Истории") { client.loadStories() }
        act("Эфир") { client.startCall() }
        act("Камера эфира") { client.callState(extra.text.toString().toLongOrNull() ?: 0L, muted = false, video = true, screen = false) }
        act("Экран эфира") { client.callState(extra.text.toString().toLongOrNull() ?: 0L, muted = false, video = false, screen = true) }
        act("Фрагмент эфира") {
            if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 21)
                toast("Разрешите микрофон и нажмите ещё раз")
            } else {
                client.recordCall(extra.text.toString().toLongOrNull() ?: 0L)
            }
        }
        act("Сохранить эфир") { client.saveCall(extra.text.toString().toLongOrNull() ?: 0L) }
        act("Сеансы") { client.loadSessions() }
        act("Завершить другие") { client.terminateOthers() }
        act("Облачный пароль") { client.setCloudPassword(extra.text.toString(), second.text.toString()) }
        act("Скрыть телефон, онлайн и фото") { client.setPrivacy("nobody", "nobody", "nobody") }
        act("Код-пароль") { client.setPin(extra.text.toString()); toast("Код сохранён на устройстве") }
        act("Запомнить аккаунт") { client.rememberAccount(extra.text.toString()) }
        act("День") { client.savePreferences("{\"theme\":\"day\",\"radius\":18}") }
        act("Ночь") { client.savePreferences("{\"theme\":\"night\",\"radius\":16}") }
        act("Бот") { client.createBot(extra.text.toString(), second.text.toString()) }
        act("Войти ботом") { client.botSignIn(extra.text.toString()) }
        act("Счёт Premium") { client.createInvoice(extra.text.toString().toLongOrNull() ?: 0L, "Premium", second.text.toString()) }
        act("Подтвердить счёт") { client.confirmInvoice(extra.text.toString().toLongOrNull() ?: 0L, second.text.toString()) }
        act("Счета") { client.loadInvoices() }
        act("Мини-приложение") { client.createApp(extra.text.toString(), second.text.toString().ifBlank { "app" }, "https://example.com") }
        act("HTML мини-приложения") { client.setAppContent(second.text.toString().ifBlank { "app" }, "<!doctype html><meta charset=utf-8><p>${extra.text}</p>") }
        act("Открыть мини-приложение") { client.openMiniApp(second.text.toString().ifBlank { "app" }) }
        act("Яркость файла на сервере") { client.editServerFile(extra.text.toString().toLongOrNull() ?: 0L, second.text.toString().toIntOrNull() ?: 10) }
        act("Приложения") { client.loadApps() }
        act("Стикеры") { client.loadStickers() }
        act("Вкладка Избранного") { client.createSavedTab(extra.text.toString()) }
        act("Тег сообщения") { client.tagSaved(second.text.toString().toLongOrNull() ?: 0L, extra.text.toString()) }
        act("Выйти") { client.signOut() }
        if (boardLines.isNotEmpty()) column.addView(label(boardTitle, 16f, C_TEXT))
        for (line in boardLines) column.addView(label(line, 14f, C_MUTED))
    }

    private class DialogAdapter(val onClick: (DialogRow) -> Unit) : RecyclerView.Adapter<DialogHolder>() {
        private var rows = listOf<DialogRow>()
        fun submit(next: List<DialogRow>) {
            rows = next
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) = DialogHolder(parent)
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(holder: DialogHolder, position: Int) = holder.bind(rows[position], onClick)
    }

    private class MessageAdapter(
        val onOlder: () -> Unit,
        val onBubble: (ChatLine.Bubble) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows = listOf<ChatLine>()
        fun submit(next: List<ChatLine>) {
            rows = next
            notifyDataSetChanged()
        }
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is ChatLine.Bubble -> 2
            is ChatLine.Day -> 1
            is ChatLine.Older -> 0
        }
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context)
            return object : RecyclerView.ViewHolder(tv) {}
        }
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView as TextView
            val width = holder.itemView.resources.displayMetrics.widthPixels
            when (val row = rows[position]) {
                is ChatLine.Older -> {
                    view.text = if (row.loading) "Загрузка…" else "Ранние сообщения"
                    view.gravity = Gravity.CENTER
                    view.setTextColor(C_ACCENT)
                    view.setPadding(0, dpStatic(view, 12), 0, dpStatic(view, 12))
                    view.background = null
                    view.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                    view.setOnClickListener { if (!row.loading) onOlder() }
                }
                is ChatLine.Day -> {
                    view.text = row.label
                    view.gravity = Gravity.CENTER
                    view.setTextColor(C_MUTED)
                    view.setPadding(0, dpStatic(view, 10), 0, dpStatic(view, 6))
                    view.background = null
                    view.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                    view.setOnClickListener(null)
                }
                is ChatLine.Bubble -> {
                    val name = if (row.showName && row.sender.isNotBlank()) row.sender + "\n" else ""
                    view.text = name + row.text + "\n" + row.time
                    view.setTextColor(C_TEXT)
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    view.background = GradientDrawable().apply {
                        setColor(if (row.mine) C_OUT else C_IN)
                        cornerRadius = dpStatic(view, 16).toFloat()
                    }
                    val pad = dpStatic(view, 10)
                    view.setPadding(pad, pad, pad, pad)
                    val params = RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                    params.topMargin = dpStatic(view, 3)
                    params.bottomMargin = dpStatic(view, 3)
                    params.marginStart = if (row.mine) width / 4 else dpStatic(view, 12)
                    params.marginEnd = if (row.mine) dpStatic(view, 12) else width / 4
                    view.layoutParams = params
                    view.setOnClickListener { onBubble(row) }
                }
            }
        }
    }

    private class PersonAdapter(
        val checkable: Boolean,
        val picked: MutableSet<Long>,
        val onClick: (Person) -> Unit,
        val onContact: (Person) -> Unit,
    ) : RecyclerView.Adapter<PersonHolder>() {
        private var rows = listOf<Person>()
        fun submit(next: List<Person>) {
            rows = next
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) = PersonHolder(parent)
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(holder: PersonHolder, position: Int) {
            holder.bind(rows[position], checkable && picked.contains(rows[position].id), onClick)
        }
    }
}

private class DialogHolder(parent: android.view.ViewGroup) : RecyclerView.ViewHolder(row(parent)) {
    private val title = TextView(parent.context)
    private val preview = TextView(parent.context)
    private val time = TextView(parent.context)
    private val badge = TextView(parent.context)
    init {
        val box = itemView as LinearLayout
        val text = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
        title.setTextColor(C_TEXT)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        preview.setTextColor(C_MUTED)
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        preview.maxLines = 1
        time.setTextColor(C_MUTED)
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        badge.setTextColor(C_TEXT)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        badge.gravity = Gravity.CENTER
        badge.background = GradientDrawable().apply {
            setColor(C_ACCENT)
            cornerRadius = 40f
        }
        val top = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(time)
        }
        val bottom = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(preview, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(badge)
        }
        text.addView(top)
        text.addView(bottom)
        box.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    fun bind(row: DialogRow, onClick: (DialogRow) -> Unit) {
        title.text = row.title
        preview.text = row.preview
        time.text = row.time
        badge.text = if (row.unread > 99) "99+" else row.unread.toString()
        badge.visibility = if (row.unread > 0) View.VISIBLE else View.GONE
        itemView.setOnClickListener { onClick(row) }
    }
}

private class PersonHolder(parent: android.view.ViewGroup) : RecyclerView.ViewHolder(row(parent)) {
    private val title = TextView(parent.context)
    private val sub = TextView(parent.context)
    init {
        val box = itemView as LinearLayout
        val text = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
        title.setTextColor(C_TEXT)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        sub.setTextColor(C_MUTED)
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        text.addView(title)
        text.addView(sub)
        box.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    fun bind(person: Person, picked: Boolean, onClick: (Person) -> Unit) {
        val name = person.displayName.ifBlank { person.username }
        title.text = if (picked) "✓  $name" else name
        val role = if (person.role == "owner") "владелец" else if (person.role == "member") "участник" else ""
        sub.text = "@${person.username}" + (if (person.online) "  ·  в сети" else "") + (if (role.isBlank()) "" else "  ·  $role")
        itemView.setOnClickListener { onClick(person) }
    }
}

private fun row(parent: android.view.ViewGroup) = LinearLayout(parent.context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dpStatic(this, 16), dpStatic(this, 10), dpStatic(this, 16), dpStatic(this, 10))
    layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
    setBackgroundColor(C_WINDOW)
}

private fun dpStatic(view: View, v: Int) = (v * view.resources.displayMetrics.density).toInt()

private class SimpleText(val onChange: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChange(s?.toString().orEmpty())
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}

private const val C_WINDOW = 0xFF0E1621.toInt()
private const val C_HEADER = 0xFF17212B.toInt()
private const val C_ACCENT = 0xFF2AABEE.toInt()
private const val C_TEXT = 0xFFF5F5F5.toInt()
private const val C_MUTED = 0xFF7F91A4.toInt()
private const val C_INPUT = 0xFF242F3D.toInt()
private const val C_OUT = 0xFF2B5278.toInt()
private const val C_IN = 0xFF182533.toInt()
private const val C_DANGER = 0xFFE55B5B.toInt()
private const val C_ONLINE = 0xFF0AC630.toInt()
