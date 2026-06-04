package com.example.lunartearlauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView

class LauncherUi(
    private val activity: Activity,
    private val host: Host
) {
    interface Host {
        fun currentIpMode(): IpMode
        fun currentManualIp(): String
        fun readinessText(): String
        fun statusValueText(): String
        fun assetStateValueText(): String
        fun dbStateValueText(): String
        fun ipModeLabel(): String
        fun assetImportStatusText(): String
        fun logTailText(): String
        fun onIpModeChanged(mode: IpMode)
        fun onManualIpChanged(value: String)
        fun onPickAssetsFolder()
        fun onPickAssetsTar()
        fun onCreateDbZip()
        fun onPickDbZip()
        fun onStartServer(mode: IpMode, manualIp: String)
        fun onStopServer()
        fun onRefresh()
        fun onClearLogs()
    }

    private lateinit var contentFrame: FrameLayout
    private lateinit var status: TextView
    private var manualIp: EditText? = null
    private var modeGroup: RadioGroup? = null
    private var currentScreen = Screen.HOME

    private var homeStatusValue: TextView? = null
    private var homeAssetsValue: TextView? = null
    private var homeDbValue: TextView? = null
    private var homeIpValue: TextView? = null
    private var logText: TextView? = null
    private var importProgressValue: TextView? = null
    private val ipButtonModes = mutableMapOf<Int, IpMode>()

    fun buildShell() {
        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(color("#10131A"))
        }
        shell.addView(rightMenu(), LinearLayout.LayoutParams(activity.dp(116), ViewGroup.LayoutParams.MATCH_PARENT))

        contentFrame = FrameLayout(activity)
        shell.addView(
            contentFrame,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        activity.setContentView(shell)
    }

    fun showScreen(screen: Screen) {
        currentScreen = screen
        contentFrame.removeAllViews()
        homeStatusValue = null
        homeAssetsValue = null
        homeDbValue = null
        homeIpValue = null
        logText = null
        importProgressValue = null
        manualIp = null
        modeGroup = null
        ipButtonModes.clear()

        val screenRoot = when (screen) {
            Screen.HOME -> homeScreen()
            Screen.LOGS -> logsScreen()
            Screen.SETTINGS -> settingsScreen()
        }
        contentFrame.addView(
            screenRoot,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        rebuildRightMenu()
        updateDynamicViews()
    }

    fun showNotice(text: String) {
        if (::status.isInitialized) status.text = text
        updateDynamicViews()
    }

    fun updateDynamicViews() {
        homeStatusValue?.text = host.statusValueText()
        homeAssetsValue?.text = host.assetStateValueText()
        homeDbValue?.text = host.dbStateValueText()
        homeIpValue?.text = host.ipModeLabel()
        importProgressValue?.text = "Import status\n${host.assetImportStatusText()}"
        logText?.text = host.logTailText()
    }

    private fun rightMenu(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(activity.dp(10), activity.dp(28), activity.dp(10), activity.dp(16))
            background = rounded(color("#171B24"), activity.dp(0))

            addView(TextView(activity).apply {
                text = "Lunar\nTear"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, activity.dp(18))
            })

            addView(menuButton("Main", Screen.HOME))
            addView(menuButton("Logs", Screen.LOGS))
            addView(menuButton("Settings", Screen.SETTINGS))

            addView(Space(activity), LinearLayout.LayoutParams(1, 0, 1f))

            addView(TextView(activity).apply {
                text = "v0.5.1"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(color("#8F96A3"))
            })
        }
    }

    private fun menuButton(title: String, target: Screen): Button {
        return Button(activity).apply {
            text = title
            textSize = 12f
            isAllCaps = false
            setTextColor(if (currentScreen == target) Color.WHITE else color("#D7DCE6"))
            background = rounded(
                if (currentScreen == target) color("#2D6BFF") else color("#222837"),
                activity.dp(14)
            )
            setPadding(activity.dp(4), 0, activity.dp(4), 0)
            setOnClickListener { showScreen(target) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dp(48)
            ).apply { bottomMargin = activity.dp(10) }
        }
    }

    private fun rebuildRightMenu() {
        val shell = contentFrame.parent as? LinearLayout ?: return
        if (shell.childCount >= 2) shell.removeViewAt(0)
        shell.addView(rightMenu(), 0, LinearLayout.LayoutParams(activity.dp(116), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun baseScreen(title: String, subtitle: String): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(26), activity.dp(18), activity.dp(24))
            setBackgroundColor(color("#10131A"))

            addView(TextView(activity).apply {
                text = title
                textSize = 26f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })

            addView(TextView(activity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(color("#A9B0BE"))
                setPadding(0, activity.dp(4), 0, activity.dp(14))
            })

            status = TextView(activity).apply {
                text = host.readinessText()
                textSize = 13f
                setTextColor(color("#D7DCE6"))
                setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
                background = rounded(color("#171B24"), activity.dp(16))
            }
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = activity.dp(14) })
        }
    }

    private fun homeScreen(): ScrollView {
        val root = baseScreen(
            title = "Main",
            subtitle = "Server status, assets, database, and quick launch."
        )

        val cards = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        homeStatusValue = statCard(cards, "Staus", host.statusValueText())
        homeAssetsValue = statCard(cards, "Assets", host.assetStateValueText())
        homeDbValue = statCard(cards, "DB", host.dbStateValueText())
        homeIpValue = statCard(cards, "IP mode", host.ipModeLabel())
        root.addView(cards)

        root.addView(sectionTitle("IP mode"))
        modeGroup = RadioGroup(activity).apply { orientation = LinearLayout.VERTICAL }
        val local = radio("127.0.0.1 - client on this phone", IpMode.LOCAL)
        val lan = radio("LAN IP - another device in same Wi-Fi", IpMode.LAN)
        val manual = radio("Manual IP", IpMode.MANUAL)
        modeGroup?.addView(local)
        modeGroup?.addView(lan)
        modeGroup?.addView(manual)
        modeGroup?.setOnCheckedChangeListener { _, checkedId ->
            ipButtonModes[checkedId]?.let { mode ->
                host.onIpModeChanged(mode)
                updateDynamicViews()
            }
        }
        root.addView(modeGroup)

        manualIp = EditText(activity).apply {
            hint = "Manual IP, for example 192.168.1.50"
            setText(host.currentManualIp())
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(color("#737B8A"))
            background = rounded(color("#171B24"), activity.dp(14))
            setPadding(activity.dp(14), 0, activity.dp(14), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    host.onManualIpChanged(s?.toString().orEmpty())
                    updateDynamicViews()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(manualIp, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            activity.dp(50)
        ).apply { topMargin = activity.dp(8); bottomMargin = activity.dp(14) })

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("Start server") {
            host.onStartServer(selectedIpMode(), manualIp?.text?.toString().orEmpty())
        }, LinearLayout.LayoutParams(0, activity.dp(52), 1f).apply { rightMargin = activity.dp(8) })
        buttons.addView(actionButton("Stop server") { host.onStopServer() }, LinearLayout.LayoutParams(0, activity.dp(52), 1f).apply { leftMargin = activity.dp(8) })
        root.addView(buttons)

        return ScrollView(activity).apply { addView(root) }
    }

    private fun logsScreen(): ScrollView {
        val root = baseScreen(
            title = "Logs",
            subtitle = "Tail launcher.log plus quick refresh and clear logs commands."
        )

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("Refresh") { host.onRefresh() }, LinearLayout.LayoutParams(0, activity.dp(52), 1f).apply { rightMargin = activity.dp(8) })
        buttons.addView(actionButton("Clear logs") { host.onClearLogs() }, LinearLayout.LayoutParams(0, activity.dp(52), 1f).apply { leftMargin = activity.dp(8) })
        root.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = activity.dp(14) })

        val logs = TextView(activity).apply {
            textSize = 12f
            setTextColor(color("#D7DCE6"))
            setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
            background = rounded(color("#0B0E14"), activity.dp(16))
            typeface = Typeface.MONOSPACE
        }
        logText = logs
        root.addView(logs, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = activity.dp(56) })

        return ScrollView(activity).apply { addView(root) }
    }

    private fun settingsScreen(): ScrollView {
        val root = baseScreen(
            title = "Settings",
            subtitle = "Importing assets, assets.tar, and migrating the database."
        )

        root.addView(sectionTitle("Assets"))
        val progress = infoCard("Import status", host.assetImportStatusText())
        importProgressValue = progress
        root.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = activity.dp(10) })
        root.addView(actionButton("Import asset folder") { host.onPickAssetsFolder() })
        root.addView(actionButton("Import assets.tar") { host.onPickAssetsTar() })

        root.addView(sectionTitle("Database"))
        root.addView(actionButton("Export db.zip") { host.onCreateDbZip() })
        root.addView(actionButton("Import db.zip") { host.onPickDbZip() })

        root.addView(sectionTitle("Storage"))
        root.addView(infoCard("Server folder", activity.filesDir.resolve("server").absolutePath))

        return ScrollView(activity).apply { addView(root) }
    }

    private fun statCard(parent: LinearLayout, label: String, value: String): TextView {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            background = rounded(color("#171B24"), activity.dp(16))
        }
        box.addView(TextView(activity).apply {
            text = label
            textSize = 12f
            setTextColor(color("#8F96A3"))
        })
        val valueView = TextView(activity).apply {
            text = value
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, activity.dp(2), 0, 0)
        }
        box.addView(valueView)
        parent.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = activity.dp(10) })
        return valueView
    }

    private fun infoCard(label: String, value: String): TextView {
        return TextView(activity).apply {
            text = "$label\n$value"
            textSize = 13f
            setTextColor(color("#D7DCE6"))
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            background = rounded(color("#171B24"), activity.dp(16))
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, activity.dp(18), 0, activity.dp(8))
        }
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button {
        return Button(activity).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(color("#2D6BFF"), activity.dp(16))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dp(52)
            ).apply { bottomMargin = activity.dp(10) }
        }
    }

    private fun radio(text: String, mode: IpMode): RadioButton {
        return RadioButton(activity).apply {
            id = View.generateViewId()
            ipButtonModes[id] = mode
            this.text = text
            isChecked = host.currentIpMode() == mode
            textSize = 14f
            setTextColor(color("#D7DCE6"))
            buttonTintList = android.content.res.ColorStateList.valueOf(color("#6A92FF"))
        }
    }

    private fun selectedIpMode(): IpMode {
        val checkedId = modeGroup?.checkedRadioButtonId ?: return host.currentIpMode()
        return ipButtonModes[checkedId] ?: host.currentIpMode()
    }
}
