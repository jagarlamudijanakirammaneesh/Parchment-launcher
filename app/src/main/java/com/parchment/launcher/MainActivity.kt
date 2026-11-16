package com.parchment.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    private enum class Screen {
        HOME,
        ALL_APPS
    }

    private val apps = mutableListOf<ResolveInfo>()
    private val searchHandler = Handler(Looper.getMainLooper())

    private lateinit var screenContent: LinearLayout
    private var homeShortcutList: LinearLayout? = null
    private var currentScreen = Screen.HOME
    private var pendingAutoLaunch: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        loadApps()
        showHome()
    }

    override fun onResume() {
        super.onResume()

        if (::screenContent.isInitialized) {
            loadApps()

            if (currentScreen == Screen.HOME) {
                showHome()
            } else {
                showAllApps()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showHome()
    }

    @Deprecated("Use the system back dispatcher")
    override fun onBackPressed() {
        if (currentScreen == Screen.ALL_APPS) {
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        cancelPendingAutoLaunch()
        super.onDestroy()
    }

    private fun loadApps() {
        val query = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        apps.clear()
        apps.addAll(
            packageManager.queryIntentActivities(query, 0)
                .filter { app ->
                    app.activityInfo.packageName != packageName
                }
                .sortedBy { app ->
                    app.loadLabel(packageManager)
                        .toString()
                        .lowercase(Locale.getDefault())
                }
        )
    }

    private fun showHome() {
        cancelPendingAutoLaunch()
        currentScreen = Screen.HOME

        val content = beginScreen()
        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val page = vertical().apply {
            setPadding(0, dp(24), 0, dp(14))
        }

        page.addView(
            text(greeting(), 38, TEXT).apply {
                letterSpacing = -0.02f
            }
        )

        val subtitle = text(
            "A little less noise. A little more focus.",
            14,
            MUTED
        )
        val subtitleParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
            bottomMargin = dp(20)
        }
        page.addView(subtitle, subtitleParams)

        val search = searchField("Search apps")
        page.addView(
            search,
            LinearLayout.LayoutParams(-1, dp(48))
        )

        val sectionHeading = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(8))
        }

        sectionHeading.addView(
            text("Your apps", 20, TEXT),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        sectionHeading.addView(
            actionText("CHOOSE  +") {
                showHomeAppPicker()
            }
        )

        sectionHeading.addView(
            actionText("ALL APPS  →") {
                showAllApps()
            }
        )

        page.addView(sectionHeading)
        page.addView(rule())

        homeShortcutList = vertical()
        page.addView(
            homeShortcutList,
            LinearLayout.LayoutParams(-1, -2)
        )

        renderHomeShortcuts("")

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                val query = text?.toString().orEmpty()
                renderHomeShortcuts(query)
                openIfSingleSearchResult(query)
            }

            override fun afterTextChanged(text: Editable?) = Unit
        })

        scrollView.addView(page)
        content.addView(
            scrollView,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
    }

    private fun renderHomeShortcuts(filter: String) {
        val list = homeShortcutList ?: return
        list.removeAllViews()

        val query = filter.trim().lowercase(Locale.getDefault())

        val visibleApps = if (query.isEmpty()) {
            selectedHomeApps()
        } else {
            apps.filter { app ->
                app.loadLabel(packageManager)
                    .toString()
                    .lowercase(Locale.getDefault())
                    .contains(query)
            }.take(MAX_HOME_APPS)
        }

        visibleApps.forEach { app ->
            val row = appNameRow(app)
            list.addView(row)
            list.addView(rule())
        }

        if (visibleApps.isEmpty()) {
            val message = if (query.isEmpty()) {
                "Choose apps for your home screen"
            } else {
                "No matching apps"
            }

            list.addView(text(message, 14, MUTED).apply {
                setPadding(dp(4), dp(24), 0, dp(24))

                if (query.isEmpty()) {
                    setOnClickListener {
                        showHomeAppPicker()
                    }
                }
            })
        }
    }

    private fun showHomeAppPicker() {
        if (apps.isEmpty()) {
            Toast.makeText(
                this,
                "No launchable apps were found",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val availableKeys = apps.map { app ->
            homeAppKey(app)
        }.toSet()

        val chosenKeys = selectedHomeAppKeys()
            .filter { key -> key in availableKeys }
            .take(MAX_HOME_APPS)
            .toMutableSet()

        val labels = apps.map { app ->
            app.loadLabel(packageManager).toString()
        }.toTypedArray()

        val checkedItems = apps.map { app ->
            homeAppKey(app) in chosenKeys
        }.toBooleanArray()

        AlertDialog.Builder(
            this,
            android.R.style.Theme_Material_Dialog_Alert
        )
            .setTitle("Choose up to 5 apps")
            .setMultiChoiceItems(labels, checkedItems) { dialog, index, checked ->
                val key = homeAppKey(apps[index])

                if (checked) {
                    if (chosenKeys.size >= MAX_HOME_APPS) {
                        (dialog as AlertDialog)
                            .listView
                            .setItemChecked(index, false)

                        Toast.makeText(
                            this,
                            "Choose up to five apps",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        chosenKeys.add(key)
                    }
                } else {
                    chosenKeys.remove(key)
                }
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ ->
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putStringSet(
                        HOME_APPS_KEY,
                        chosenKeys.toMutableSet()
                    )
                    .apply()

                renderHomeShortcuts("")
            }
            .show()
    }

    private fun selectedHomeAppKeys(): Set<String> {
        val savedKeys = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getStringSet(HOME_APPS_KEY, null)

        if (savedKeys != null) {
            return savedKeys.toSet()
        }

        return apps
            .take(MAX_HOME_APPS)
            .map { app -> homeAppKey(app) }
            .toSet()
    }

    private fun selectedHomeApps(): List<ResolveInfo> {
        val chosenKeys = selectedHomeAppKeys()

        return apps
            .filter { app -> homeAppKey(app) in chosenKeys }
            .take(MAX_HOME_APPS)
    }

    private fun homeAppKey(app: ResolveInfo): String {
        return ComponentName(
            app.activityInfo.packageName,
            app.activityInfo.name
        ).flattenToString()
    }

    private fun showAllApps() {
        cancelPendingAutoLaunch()
        currentScreen = Screen.ALL_APPS

        val content = beginScreen()
        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val page = vertical().apply {
            setPadding(0, dp(20), 0, dp(14))
        }

        page.addView(text("All apps", 36, TEXT))

        val subtitle = text(
            "Everything in one quiet place.",
            14,
            MUTED
        )
        val subtitleParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(3)
            bottomMargin = dp(16)
        }
        page.addView(subtitle, subtitleParams)

        val search = searchField("Search apps")
        page.addView(
            search,
            LinearLayout.LayoutParams(-1, dp(46))
        )

        val appList = vertical()
        page.addView(appList)
        renderAppList(appList, "")

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                val query = text?.toString().orEmpty()
                renderAppList(appList, query)
                openIfSingleSearchResult(query)
            }

            override fun afterTextChanged(text: Editable?) = Unit
        })

        scrollView.addView(page)
        content.addView(
            scrollView,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
    }

    private fun renderAppList(list: LinearLayout, filter: String) {
        list.removeAllViews()

        val query = filter.trim().lowercase(Locale.getDefault())
        val matchingApps = apps.filter { app ->
            app.loadLabel(packageManager)
                .toString()
                .lowercase(Locale.getDefault())
                .contains(query)
        }

        var previousLetter = ""

        matchingApps.forEach { app ->
            val name = app.loadLabel(packageManager).toString()
            val letter = name.firstOrNull()
                ?.uppercaseChar()
                ?.toString()
                ?: "#"

            if (letter != previousLetter) {
                previousLetter = letter

                list.addView(
                    text(letter, 15, MUTED).apply {
                        typeface = Typeface.create(
                            "serif",
                            Typeface.BOLD
                        )
                        setPadding(dp(3), dp(13), 0, dp(5))
                    }
                )
                list.addView(rule())
            }

            list.addView(appNameRow(app))
            list.addView(rule())
        }

        if (matchingApps.isEmpty()) {
            list.addView(text("No matching apps", 14, MUTED).apply {
                setPadding(dp(4), dp(24), 0, dp(24))
            })
        }
    }

    private fun appNameRow(app: ResolveInfo): TextView {
        return TextView(this).apply {
            text = app.loadLabel(packageManager).toString()
            textSize = 17f
            setTextColor(TEXT)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(10), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                launchApp(app)
            }
        }
    }

    private fun openIfSingleSearchResult(query: String) {
        cancelPendingAutoLaunch()

        val searchText = query.trim()
        if (searchText.isEmpty()) return

        val matches = apps.filter { app ->
            app.loadLabel(packageManager)
                .toString()
                .contains(searchText, ignoreCase = true)
        }

        if (matches.size != 1) return

        val app = matches.single()
        val launchTask = Runnable {
            pendingAutoLaunch = null
            launchApp(app)
        }

        pendingAutoLaunch = launchTask
        searchHandler.postDelayed(launchTask, AUTO_LAUNCH_DELAY_MS)
    }

    private fun cancelPendingAutoLaunch() {
        pendingAutoLaunch?.let { task ->
            searchHandler.removeCallbacks(task)
        }
        pendingAutoLaunch = null
    }

    private fun launchApp(app: ResolveInfo) {
        cancelPendingAutoLaunch()

        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(
                ComponentName(
                    app.activityInfo.packageName,
                    app.activityInfo.name
                )
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        startActivity(launchIntent)
    }

    private fun beginScreen(): LinearLayout {
        val root = vertical().apply {
            setBackgroundColor(BACKGROUND)
            setPadding(dp(24), dp(9), dp(24), 0)
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        val date = DateFormat
            .getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .format(Date())

        header.addView(
            text("PARCHMENT", 16, TEXT).apply {
                typeface = Typeface.create("serif", Typeface.BOLD)
                letterSpacing = 0.14f
            },
            LinearLayout.LayoutParams(0, dp(38), 1f)
        )

        val dateArea = text(date, 12, MUTED).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            minWidth = dp(96)
            setPadding(dp(8), 0, dp(2), 0)
            contentDescription = "Swipe down to open notifications"
        }
        installNotificationSwipe(dateArea)
        header.addView(dateArea)

        root.addView(header)
        root.addView(rule())

        val content = vertical()
        root.addView(
            content,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        screenContent = content

        val navigation = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }

        navigation.addView(
            navItem("⌂", "Home", currentScreen == Screen.HOME) {
                showHome()
            },
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        navigation.addView(
            navItem("☷", "All apps", currentScreen == Screen.ALL_APPS) {
                showAllApps()
            },
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        root.addView(rule())
        root.addView(navigation)
        setContentView(root)
        applySystemInsets(root)

        return content
    }

    private fun installNotificationSwipe(target: View) {
        var startX = 0f
        var startY = 0f

        target.isClickable = true
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val distanceDown = event.y - startY
                    val distanceAcross = abs(event.x - startX)

                    if (
                        distanceDown >= dp(48) &&
                        distanceDown > distanceAcross
                    ) {
                        startActivity(
                            Intent(
                                this,
                                NotificationDrawerActivity::class.java
                            )
                        )
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun applySystemInsets(root: View) {
        if (Build.VERSION.SDK_INT < 35) return

        window.setDecorFitsSystemWindows(false)

        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())

            view.setPadding(
                dp(24) + bars.left,
                dp(9) + bars.top,
                dp(24) + bars.right,
                bars.bottom
            )

            insets
        }

        root.requestApplyInsets()
    }

    private fun navItem(
        symbol: String,
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ): View {
        return vertical().apply {
            gravity = Gravity.CENTER

            addView(
                text(symbol, 20, if (selected) TEXT else MUTED).apply {
                    gravity = Gravity.CENTER
                }
            )

            addView(
                text(label, 11, if (selected) TEXT else MUTED).apply {
                    gravity = Gravity.CENTER

                    if (selected) {
                        typeface = Typeface.create(
                            "serif",
                            Typeface.BOLD
                        )
                    }
                }
            )

            setOnClickListener {
                onClick()
            }
        }
    }

    private fun actionText(
        label: String,
        onClick: () -> Unit
    ): TextView {
        return text(label, 12, MUTED).apply {
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener {
                onClick()
            }
        }
    }

    private fun searchField(label: String): EditText {
        return EditText(this).apply {
            hint = "⌕   $label"
            textSize = 16f
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = searchBackground()
        }
    }

    private fun vertical(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
    }

    private fun greeting(): String {
        val hour = Calendar.getInstance()
            .get(Calendar.HOUR_OF_DAY)

        return when (hour) {
            in 0..11 -> "Good morning."
            in 12..17 -> "Good afternoon."
            else -> "Good evening."
        }
    }

    private fun text(
        value: String,
        size: Int,
        color: Int
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
        }
    }

    private fun searchBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(SURFACE)
            cornerRadius = dp(3).toFloat()
            setStroke(dp(1), RULE)
        }
    }

    private fun rule(): View {
        return View(this).apply {
            setBackgroundColor(RULE)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        private const val PREFERENCES = "parchment"
        private const val HOME_APPS_KEY = "home_apps"
        private const val MAX_HOME_APPS = 5
        private const val AUTO_LAUNCH_DELAY_MS = 500L

        private val BACKGROUND = Color.rgb(18, 18, 18)
        private val SURFACE = Color.rgb(30, 30, 30)
        private val TEXT = Color.rgb(245, 245, 245)
        private val MUTED = Color.rgb(175, 175, 175)
        private val RULE = Color.rgb(68, 68, 68)
    }
}