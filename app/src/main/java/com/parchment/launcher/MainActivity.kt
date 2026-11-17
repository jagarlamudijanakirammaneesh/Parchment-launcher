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
import android.widget.ImageView
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

    private fun visibleApps(): List<ResolveInfo> {
        val hiddenKeys = getHiddenAppKeys()

        return apps.filter { app ->
            homeAppKey(app) !in hiddenKeys
        }
    }

    private fun getHiddenAppKeys(): Set<String> {
        return getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getStringSet(HIDDEN_APPS_KEY, emptySet())
            ?.toSet()
            ?: emptySet()
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



        page.addView(
            text("“${dailyQuote()}”", 15, MUTED).apply {
                typeface = Typeface.SERIF
                maxLines = 3
                setPadding(0, 0, 0, dp(18))
            }
        )

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

        val visibleHomeApps = if (query.isEmpty()) {
            selectedHomeApps()
        } else {
            visibleApps().filter { app ->
                app.loadLabel(packageManager)
                    .toString()
                    .lowercase(Locale.getDefault())
                    .contains(query)
            }.take(MAX_HOME_APPS)
        }

        visibleHomeApps.forEach { app ->
            list.addView(appNameRow(app))
            list.addView(rule())
        }

        if (visibleHomeApps.isEmpty()) {
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
        val availableApps = visibleApps()

        if (availableApps.isEmpty()) {
            Toast.makeText(
                this,
                "No visible apps were found",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val availableKeys = availableApps
            .map { app -> homeAppKey(app) }
            .toSet()

        val chosenKeys = selectedHomeAppKeys()
            .filter { key -> key in availableKeys }
            .take(MAX_HOME_APPS)
            .toMutableSet()

        val labels = availableApps.map { app ->
            app.loadLabel(packageManager).toString()
        }.toTypedArray()

        val checkedItems = availableApps.map { app ->
            homeAppKey(app) in chosenKeys
        }.toBooleanArray()

        AlertDialog.Builder(
            this,
            android.R.style.Theme_Material_Dialog_Alert
        )
            .setTitle("Choose up to 5 apps")
            .setMultiChoiceItems(labels, checkedItems) { dialog, index, checked ->
                val key = homeAppKey(availableApps[index])

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

    private fun showHideAppPicker() {
        if (apps.isEmpty()) {
            Toast.makeText(
                this,
                "No launchable apps were found",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val currentHiddenKeys = getHiddenAppKeys().toMutableSet()

        val labels = apps.map { app ->
            app.loadLabel(packageManager).toString()
        }.toTypedArray()

        val checkedItems = apps.map { app ->
            homeAppKey(app) in currentHiddenKeys
        }.toBooleanArray()

        AlertDialog.Builder(
            this,
            android.R.style.Theme_Material_Dialog_Alert
        )
            .setTitle("Hide apps")
            .setMultiChoiceItems(labels, checkedItems) { _, index, checked ->
                val key = homeAppKey(apps[index])

                if (checked) {
                    currentHiddenKeys.add(key)
                } else {
                    currentHiddenKeys.remove(key)
                }
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ ->
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putStringSet(
                        HIDDEN_APPS_KEY,
                        currentHiddenKeys
                    )
                    .apply()

                showAllApps()
            }
            .show()
    }

    private fun selectedHomeAppKeys(): Set<String> {
        val savedKeys = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getStringSet(HOME_APPS_KEY, null)

        if (savedKeys != null) {
            return savedKeys.toSet()
        }

        return visibleApps()
            .take(MAX_HOME_APPS)
            .map { app -> homeAppKey(app) }
            .toSet()
    }

    private fun selectedHomeApps(): List<ResolveInfo> {
        val chosenKeys = selectedHomeAppKeys()

        return visibleApps()
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

        val page = vertical().apply {
            setPadding(0, dp(20), 0, dp(14))
        }

        page.addView(text("All apps", 36, TEXT))

        page.addView(
            text("Everything in one quiet place.", 14, MUTED),
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(3)
                bottomMargin = dp(16)
            }
        )

        val search = searchField("Search apps")
        page.addView(
            search,
            LinearLayout.LayoutParams(-1, dp(46))
        )

        val listActions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        listActions.addView(
            text("APP LIST", 11, MUTED),
            LinearLayout.LayoutParams(0, dp(40), 1f)
        )

        listActions.addView(
            actionText("HIDE APPS") {
                showHideAppPicker()
            }
        )

        page.addView(listActions)

        val appScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }

        val appList = vertical()
        appScroll.addView(appList)

        val alphabetIndex = vertical().apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(3))
        }

        val resultsArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        resultsArea.addView(
            appScroll,
            LinearLayout.LayoutParams(0, -1, 1f)
        )

        resultsArea.addView(
            alphabetIndex,
            LinearLayout.LayoutParams(dp(26), -1)
        )

        page.addView(
            resultsArea,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        renderAppList(appList, "", alphabetIndex, appScroll)

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

                renderAppList(
                    appList,
                    query,
                    alphabetIndex,
                    appScroll
                )

                openIfSingleSearchResult(query)
            }

            override fun afterTextChanged(text: Editable?) = Unit
        })

        content.addView(
            page,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
    }

    private fun renderAppList(
        list: LinearLayout,
        filter: String,
        alphabetIndex: LinearLayout,
        appScroll: ScrollView
    ) {
        list.removeAllViews()

        val query = filter.trim().lowercase(Locale.getDefault())

        val matchingApps = visibleApps().filter { app ->
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

        renderAlphabetIndex(alphabetIndex, matchingApps)
        installAlphabetScrubbing(alphabetIndex, appScroll, list)
    }

    private fun renderAlphabetIndex(
        alphabetIndex: LinearLayout,
        matchingApps: List<ResolveInfo>
    ) {
        alphabetIndex.removeAllViews()

        val letters = matchingApps
            .mapNotNull { app ->
                app.loadLabel(packageManager)
                    .toString()
                    .firstOrNull()
                    ?.uppercaseChar()
                    ?.takeIf { it.isLetter() }
            }
            .distinct()
            .sorted()

        letters.forEach { letter ->
            alphabetIndex.addView(
                text(letter.toString(), 10, MUTED).apply {
                    gravity = Gravity.CENTER
                    typeface = Typeface.create(
                        "serif",
                        Typeface.BOLD
                    )
                    isClickable = true
                    isFocusable = true
                },
                LinearLayout.LayoutParams(-1, 0, 1f)
            )
        }
    }

    private fun installAlphabetScrubbing(
        alphabetIndex: LinearLayout,
        appScroll: ScrollView,
        appList: LinearLayout
    ) {
        val indexLetters = (0 until alphabetIndex.childCount)
            .mapNotNull { alphabetIndex.getChildAt(it) as? TextView }
            .filter { label ->
                val value = label.text.toString().trim()
                value.length == 1 && value[0].isLetter()
            }

        if (indexLetters.isEmpty()) return

        fun scrollToLetter(y: Float) {
            if (alphabetIndex.height <= 0) return

            val safeY = y.coerceIn(
                0f,
                alphabetIndex.height.toFloat()
            )

            val selectedLetter = indexLetters
                .minByOrNull { label ->
                    abs((label.top + label.height / 2f) - safeY)
                }
                ?.text
                ?.toString()
                ?.trim()
                ?.firstOrNull()
                ?.uppercaseChar()
                ?: return

            val section = (0 until appList.childCount)
                .mapNotNull { appList.getChildAt(it) as? TextView }
                .mapNotNull { heading ->
                    val value = heading.text.toString().trim()

                    if (value.length == 1 && value[0].isLetter()) {
                        value[0].uppercaseChar() to heading
                    } else {
                        null
                    }
                }
                .minByOrNull { (letter, _) ->
                    abs(letter.code - selectedLetter.code)
                }
                ?.second
                ?: return

            var currentView: View? = section
            var scrollY = 0

            while (currentView != null && currentView !== appScroll) {
                scrollY += currentView.top
                currentView = currentView.parent as? View
            }

            appScroll.scrollTo(0, scrollY)
        }

        indexLetters.forEach { letterView ->
            letterView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP -> {
                        scrollToLetter(letterView.top + event.y)
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
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

        val matches = visibleApps().filter { app ->
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
        searchHandler.postDelayed(
            launchTask,
            AUTO_LAUNCH_DELAY_MS
        )
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

        try {
            startActivity(launchIntent)
            overridePendingTransition(0, 0)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "This app can’t be opened",
                Toast.LENGTH_SHORT
            ).show()
        }
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
                typeface = Typeface.create(
                    "serif",
                    Typeface.BOLD
                )
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
                        try {
                            startActivity(
                                Intent(
                                    this,
                                    NotificationDrawerActivity::class.java
                                )
                            )
                            overridePendingTransition(0, 0)
                        } catch (_: Exception) {
                            Toast.makeText(
                                this,
                                "Couldn’t open notifications",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
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
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars()
            )

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
                text(
                    symbol,
                    20,
                    if (selected) TEXT else MUTED
                ).apply {
                    gravity = Gravity.CENTER
                }
            )

            addView(
                text(
                    label,
                    11,
                    if (selected) TEXT else MUTED
                ).apply {
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

    private fun dailyQuote(): String {
        val dayNumber = System.currentTimeMillis() / MILLIS_PER_DAY
        val index = (dayNumber % DAILY_QUOTES.size).toInt()
        return DAILY_QUOTES[index]
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
        return (
                value * resources.displayMetrics.density + 0.5f
                ).toInt()
    }

    companion object {
        private const val PREFERENCES = "parchment"
        private const val HOME_APPS_KEY = "home_apps"
        private const val HIDDEN_APPS_KEY = "hidden_apps"
        private const val MAX_HOME_APPS = 5
        private const val AUTO_LAUNCH_DELAY_MS = 500L
        private const val MILLIS_PER_DAY = 86_400_000L

        private val BACKGROUND = Color.rgb(18, 18, 18)
        private val SURFACE = Color.rgb(30, 30, 30)
        private val TEXT = Color.rgb(245, 245, 245)
        private val MUTED = Color.rgb(175, 175, 175)
        private val RULE = Color.rgb(68, 68, 68)

        private val DAILY_QUOTES = listOf(
            "When you feel empty, there’s room for something new to enter. That emptiness can be the beginning of becoming someone different.",
            "Let an important encounter leave its mark on you—in your body, your work, and everything you carry forward.",
            "Even if death is my sentence, I’ll first make Vinland a welcoming home, with its gates open to everyone driven out or persecuted.",
            "To be young is to be foolish.",
            "War is fought not to adhere to strict code of ethics, but to win.",
            "I strive not to become a man of success, but to become a man of value.",
            "The moment you hesitate, you are fated to lose.",
            "The victor is not determined by the number of soldiers or the strength of an army, but by the strength of their will.",
            "Justice is but an illusion created by those in power.",
            "The only true freedom is that which is found within oneself.",
            "The greatest battles are fought within our own minds.",
            "In the face of adversity, true strength is revealed.",
            "A hero is not defined by their strength alone, but by the compassion in their heart.",
            "Sometimes, the greatest act of courage is knowing when to walk away.",
            "The path of revenge only leads to more pain and suffering.",
            "Love is the greatest weapon against hatred.",
            "Those who seek power often end up being consumed by it.",
            "Sacrifice is necessary in order to achieve greatness.",
            "In order to find peace, we must first confront our inner demons.",
            "The scars we carry are a testament to the battles we have fought and survived.",
            "It is not the circumstances that define us, but our response to them.",
            "The past may shape us, but it does not define who we are.",
            "Fear can either cripple us or drive us towards greatness.",
            "We are all capable of change, if only we have the courage to embrace it.",
            "The bonds of friendship are stronger than any weapon.",
            "There is no honor in taking a life, only sorrow.",
            "Sometimes, the greatest act of heroism is choosing to show mercy.",
            "Strength is not determined by physical prowess, but by one's ability to endure pain.",
            "The true measure of a man is how he treats those who are weaker than him.",
            "In the face of tragedy, hope is the only thing that keeps us going.",
            "To find peace, one must first forgive.",
            "The greatest warriors are those who fight for a cause greater than themselves.",
            "The journey to redemption begins with forgiveness.",
            "The weak can never forgive. Forgiveness is the attribute of the strong.",
            "Hate cannot drive out hate; only love can do that.",
            "The best revenge is to be unlike him who performed the injury.",
            "He who conquers others is strong; he who conquers himself is mighty.",
            "Hatred does not cease by hatred, but only by love; this is the eternal rule.",
            "It is my job to tell you the truth. It is my job to fight and win, not to lose.",
            "A yes-man is a dangerous man. He is a menace.",
            "Absolute honesty, fairness and justice.",
            "If you once show fear in front of your men, you will never be able to command.",
            "You have got to have the moral courage to stand up and tell them the facts.",
            "A true warrior doesn't need a sword.",
            "You must not become a slave to your emotions.",
            "I have no enemies.",
            "You have no enemies. No one has any enemies. There is no one it is okay to hurt.",
            "Fear is natural. Surrendering to it is a choice.",
            "A fearless man isn't one who never trembles, but one who keeps walking.",
            "It takes more courage to forgive than to take revenge.",
            "Conquering an enemy is easy. Conquering yourself is the real battle.",
            "Do not let the pain you suffered become the pain you inflict.",
            "A man who has nothing to prove has nothing to fear.",
            "The strongest warrior is the one who has the courage to choose peace when he has every reason to choose war."
        )
    }
}