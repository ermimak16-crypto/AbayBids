package com.abaybids.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import androidx.activity.addCallback
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.abaybids.app.R
import com.abaybids.app.data.AppDatabase
import com.abaybids.app.data.Contract
import com.abaybids.app.data.Document
import com.abaybids.app.data.Tender
import com.abaybids.app.data.TenderRepository
import com.abaybids.app.databinding.ActivityMainBinding
import com.abaybids.app.databinding.ItemKpiBinding
import com.abaybids.app.databinding.ItemStatusBarBinding
import com.abaybids.app.notification.NotificationScheduler
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Native Android dashboard — no WebView, no HTML/JS dependency.
 *
 * LOOK + FUNCTION parity with the Windows dashboard:
 *  - Navigation drawer (slide-out sidebar) with all 12 sections — like the
 *    Windows sidebar (Dashboard, All Tenders, Add, Clients, Products, Calendar,
 *    Reports, Analytics, Bid History, Contracts, Documents, Settings)
 *  - Dark navy + gold theme matching the Windows app
 *  - KPI cards (Total, Pending, Active, Won, Success Rate, Total Value)
 *  - Status overview bars, monthly summary bar chart
 *  - Quick Actions grid (Add Tender, Contracts, Calendar, Settings)
 *  - Upcoming deadlines, recent tenders
 *  - Native notifications (AlarmManager → status bar, even when app is closed)
 *  - FAB to add tender
 *
 * BUT fully native Android:
 *  - Material Design components (MaterialCardView, NavigationView, Toolbar, FAB)
 *  - Room DB (SQLite, offline-first)
 *  - Intent-based navigation between activities
 *  - Native Android lifecycle + background processing
 */
class MainActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private val repo by lazy { TenderRepository(this) }
    private val contractDao by lazy { AppDatabase.get(this).contractDao() }
    private val documentDao by lazy { AppDatabase.get(this).documentDao() }
    private var latestContracts: List<Contract> = emptyList()
    private var latestDocuments: List<Document> = emptyList()
    private var latestTenders: List<Tender> = emptyList()
    private var tendersLoaded = false
    private val recentAdapter = TenderAdapter { openDetail(it.id) }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate()/setContentView(): shows the
        // native Android 12+ splash (brand background + logo) that was
        // already painted by the system at process start, then hands off to
        // Theme.AbayBids automatically — no blank white flash on launch.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.app_name)
            subtitle = getString(R.string.sub_dashboard)
            setDisplayHomeAsUpEnabled(true)
        }

        // Drawer toggle (hamburger icon)
        drawerToggle = ActionBarDrawerToggle(
            this, b.drawerLayout, b.toolbar,
            R.string.app_name, R.string.app_name
        )
        b.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        // Modern back-gesture handling (replaces the deprecated
        // onBackPressed() override) so the drawer-close-on-back behavior
        // keeps working correctly under Android 13+'s predictive back
        // gesture, which we enabled app-wide in the manifest.
        onBackPressedDispatcher.addCallback(this) {
            if (b.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                b.drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                // MainActivity is the launcher/root activity, so "back" here
                // means leave the app — same behavior the old
                // super.onBackPressed() gave us.
                finish()
            }
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // RecyclerView for recent tenders
        b.recentTendersRv.layoutManager = LinearLayoutManager(this)
        b.recentTendersRv.isNestedScrollingEnabled = false
        b.recentTendersRv.adapter = recentAdapter

        // Bottom tab bar (real mobile-app navigation) + quick actions + top-bar buttons
        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_bottom_dashboard -> true // already here
                R.id.nav_bottom_tenders -> {
                    startActivity(Intent(this, TenderListActivity::class.java)); false
                }
                R.id.nav_bottom_add -> {
                    launchAddTender(null); false
                }
                R.id.nav_bottom_calendar -> {
                    startActivity(Intent(this, CalendarActivity::class.java)); false
                }
                R.id.nav_bottom_more -> {
                    b.drawerLayout.openDrawer(GravityCompat.START); false
                }
                else -> false
            }
        }
        b.btnAddTender.setOnClickListener { launchAddTender(null) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnNotifications.setOnClickListener {
            // Show deadlines list (scroll to it) or open tender list
            startActivity(Intent(this, TenderListActivity::class.java))
        }
        b.userAvatar.setOnClickListener { b.drawerLayout.openDrawer(GravityCompat.START) }
        b.viewAllTenders.setOnClickListener { startActivity(Intent(this, TenderListActivity::class.java)) }
        b.viewAllDeadlines.setOnClickListener { startActivity(Intent(this, TenderListActivity::class.java)) }
        b.viewAllContracts.setOnClickListener { startActivity(Intent(this, ContractsActivity::class.java)) }
        b.quickAddTender.setOnClickListener { launchAddTender(null) }
        b.quickCalendar.setOnClickListener { startActivity(Intent(this, CalendarActivity::class.java)) }
        b.quickReports.setOnClickListener { startActivity(Intent(this, ReportsActivity::class.java)) }
        b.quickClients.setOnClickListener { startActivity(Intent(this, ClientsActivity::class.java)) }

        // Navigation drawer item clicks
        b.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> { /* already here */ }
                R.id.nav_tenders -> startActivity(Intent(this, TenderListActivity::class.java))
                R.id.nav_add_tender -> launchAddTender(null)
                R.id.nav_clients -> startActivity(Intent(this, ClientsActivity::class.java))
                R.id.nav_products -> startActivity(Intent(this, ProductsActivity::class.java))
                R.id.nav_calendar -> startActivity(Intent(this, CalendarActivity::class.java))
                R.id.nav_reports -> startActivity(Intent(this, ReportsActivity::class.java))
                R.id.nav_analytics -> startActivity(Intent(this, AnalyticsActivity::class.java))
                R.id.nav_bid_history -> startActivity(Intent(this, BidHistoryActivity::class.java))
                R.id.nav_documents -> startActivity(Intent(this, DocumentsActivity::class.java))
                R.id.nav_contracts -> startActivity(Intent(this, ContractsActivity::class.java))
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            b.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Observe tenders + render
        lifecycleScope.launch {
            repo.observeAll().collectLatest { tenders ->
                latestTenders = tenders
                tendersLoaded = true
                renderKpis(tenders)
                renderStatusOverview(tenders)
                renderMonthlySummary(tenders)
                renderUpcomingDeadlines(tenders)
                renderNotifBadge(tenders)
                renderCpoCollection(tenders)
                renderRecentActivity()
                recentAdapter.submitList(tenders.sortedByDescending { it.createdAt }.take(5))
            }
        }

        // Observe contracts (KPI card + Ongoing Contracts list + activity feed)
        lifecycleScope.launch {
            contractDao.observeAll().collectLatest { contracts ->
                latestContracts = contracts
                if (tendersLoaded) renderKpis(latestTenders)
                renderOngoingContracts(contracts)
                renderRecentActivity()
            }
        }

        // Observe documents (activity feed only — "Document uploaded" entries)
        lifecycleScope.launch {
            documentDao.observeAll().collectLatest { documents ->
                latestDocuments = documents
                renderRecentActivity()
            }
        }
    }

    /** Show the red badge count on the bell icon = upcoming deadlines. */
    private fun renderNotifBadge(tenders: List<Tender>) {
        val upcoming = tenders.count { t ->
            val days = StatusUi.daysUntil(t.date) ?: return@count false
            days in -7..14 && (t.status == "Pending" || t.status == "Participated")
        }
        if (upcoming > 0) {
            b.notifBadge.text = if (upcoming > 9) "9+" else upcoming.toString()
            b.notifBadge.visibility = View.VISIBLE
        } else {
            b.notifBadge.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        NotificationScheduler.rescheduleAll(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        b.drawerLayout.openDrawer(GravityCompat.START)
        return true
    }

    // (Back-press handling now lives in the OnBackPressedCallback registered
    // in onCreate — see the comment there.)

    // ---------- KPI cards ----------
    private data class Kpi(val label: String, val value: String, val sub: String, val icon: Int, val color: Int)

    private fun renderKpis(tenders: List<Tender>) {
        val total = tenders.size
        val pending = tenders.count { it.status == "Pending" }
        val won = tenders.count { it.status == "Won" }
        val participated = tenders.count { it.status == "Participated" }
        val active = tenders.count { it.status == "Pending" || it.status == "Participated" }
        val wonPct = if (total > 0) (won * 100 / total) else 0

        val ongoingContracts = latestContracts.count { it.status == "Active" }
        val totalContracts = latestContracts.size

        // Same 5 cards, same icon+color per card, same order as the web
        // dashboard's KPI row — kept in exact visual parity across platforms.
        val kpis = listOf(
            Kpi(getString(R.string.kpi_total), total.toString(), getString(R.string.kpi_total_sub), R.drawable.ic_clipboard, R.color.status_participated),
            Kpi("Ongoing Contracts", ongoingContracts.toString(), "of $totalContracts total contracts", R.drawable.ic_trending_up, R.color.status_won),
            Kpi(getString(R.string.kpi_pending), pending.toString(), if (total > 0) "${pending * 100 / total}% of total" else "—", R.drawable.ic_hourglass, R.color.status_pending),
            Kpi(getString(R.string.kpi_active), active.toString(), getString(R.string.kpi_participated), R.drawable.ic_description, R.color.status_active),
            Kpi(getString(R.string.kpi_won), won.toString(), "$wonPct% of total", R.drawable.ic_trophy, R.color.brand_accent),
            Kpi(getString(R.string.kpi_success_rate), "$wonPct%", "won of total", R.drawable.ic_groups, R.color.brand_primary)
        )

        b.kpiGrid.removeAllViews()
        kpis.forEach { kpi ->
            val binding = ItemKpiBinding.inflate(LayoutInflater.from(this), b.kpiGrid, true)
            binding.kpiValue.text = kpi.value
            binding.kpiLabel.text = kpi.label
            binding.kpiSub.text = kpi.sub
            binding.kpiIcon.setImageResource(kpi.icon)
            val color = ContextCompat.getColor(this, kpi.color)
            binding.kpiIcon.setColorFilter(color)
            // Neutral chip background that reads correctly in both themes — a
            // translucent tint of the (warm gold/amber) status color muddied
            // into a "brown" chip on dark backgrounds and didn't visibly
            // change between Light/Dark. @color/bg_card_alt already has a
            // proper Light and Dark value, so the chip now actually adapts.
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8f * resources.displayMetrics.density
                setColor(ContextCompat.getColor(this@MainActivity, R.color.bg_card_alt))
            }
            binding.kpiIcon.background = bg
        }
    }

    // ---------- Tender Status Overview ----------
    private fun renderStatusOverview(tenders: List<Tender>) {
        b.statusBars.removeAllViews()
        val segments = StatusUi.order.map { status ->
            val count = tenders.count { it.status == status }
            val color = ContextCompat.getColor(this, StatusUi.color(status))
            Triple(status, count, color)
        }
        b.statusDonut.setData(
            segments.map { (_, count, color) -> StatusDonutChartView.Slice(count.toFloat(), color) },
            tenders.size.toString(),
            if (tenders.size == 1) "Tender" else "Tenders"
        )

        segments.forEach { (status, count, color) ->
            val row = ItemStatusBarBinding.inflate(LayoutInflater.from(this), b.statusBars, true)
            row.statusName.text = status
            row.statusCount.text = count.toString()
            (row.statusDot.background.mutate() as android.graphics.drawable.GradientDrawable)
                .setColor(color)
        }
    }

    // ---------- Monthly Tender Summary ----------
    private fun renderMonthlySummary(tenders: List<Tender>) {
        b.monthBars.removeAllViews()
        val year = Calendar.getInstance().get(Calendar.YEAR)
        b.yearLabel.text = year.toString()

        val counts = IntArray(12)
        tenders.forEach { t ->
            try {
                val parts = t.date.split("-")
                if (parts.size == 3 && parts[0].toInt() == year) {
                    val m = parts[1].toInt() - 1
                    if (m in 0..11) counts[m]++
                }
            } catch (_: Exception) {}
        }
        val max = counts.max().coerceAtLeast(1)
        val months = arrayOf("J","F","M","A","M","J","J","A","S","O","N","D")
        for (i in 0 until 12) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                gravity = Gravity.BOTTOM
            }
            val bar = View(this).apply {
                val h = (counts[i].toFloat() / max * 60 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h.coerceAtLeast(if (counts[i] > 0) 6 else 2)
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
                val color = if (counts[i] > 0)
                    ContextCompat.getColor(this@MainActivity, R.color.brand_primary)
                else
                    ContextCompat.getColor(this@MainActivity, R.color.divider)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 4f
                    setColor(color)
                }
            }
            col.addView(bar)
            val label = TextView(this).apply {
                text = months[i]
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                textSize = 9f
                gravity = Gravity.CENTER
                setPadding(0, 4, 0, 0)
            }
            col.addView(label)
            b.monthBars.addView(col)
        }
    }

    // ---------- Upcoming Deadlines ----------
    private fun renderUpcomingDeadlines(tenders: List<Tender>) {
        b.deadlinesList.removeAllViews()
        val upcoming = tenders
            .mapNotNull { t -> StatusUi.daysUntil(t.date)?.let { d -> t to d } }
            .filter { it.second >= -7 }
            .sortedBy { it.second }
            .take(5)

        if (upcoming.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No upcoming deadlines."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                setPadding(0, 12, 0, 12)
            }
            b.deadlinesList.addView(empty)
            return
        }
        upcoming.forEach { (t, days) ->
            val accentColor =
                if (days < 0) ContextCompat.getColor(this@MainActivity, R.color.status_lost)
                else if (days <= 1) ContextCompat.getColor(this@MainActivity, R.color.status_pending)
                else ContextCompat.getColor(this@MainActivity, R.color.status_participated)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { openDetail(t.id) }
            }
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (3 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { marginEnd = (10 * resources.displayMetrics.density).toInt() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 4f
                    setColor(accentColor)
                }
            }
            row.addView(bar)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = t.customer
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                text = "#${t.no}  ·  ${t.date}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                textSize = 12f
            })
            row.addView(info)
            val chip = TextView(this).apply {
                text = if (days < 0) "${-days}d overdue"
                       else if (days == 0) "Today"
                       else "${days}d"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(28, 12, 28, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 32f
                    setColor(accentColor)
                }
            }
            row.addView(chip)
            b.deadlinesList.addView(row)
        }
    }

    // ---------- CPO Collection ----------
    /** Tenders with a bid bond (CPO) on deposit that hasn't been collected back yet. */
    private fun renderCpoCollection(tenders: List<Tender>) {
        val pending = tenders
            .filter { it.cpo.equals("Yes", true) && !it.cpoCollected }
            .sortedBy { it.date }

        b.cpoCountBadge.text = pending.size.toString()
        b.cpoCountBadge.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE

        b.cpoList.removeAllViews()
        if (pending.isEmpty()) {
            b.cpoList.addView(TextView(this).apply {
                text = "No CPOs pending collection."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                setPadding(0, 12, 0, 12)
            })
            return
        }
        pending.take(5).forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 10)
                gravity = Gravity.CENTER_VERTICAL
            }
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (3 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { marginEnd = (10 * resources.displayMetrics.density).toInt() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 4f
                    setColor(ContextCompat.getColor(this@MainActivity, R.color.brand_accent))
                }
            }
            row.addView(bar)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener { openDetail(t.id) }
            }
            info.addView(TextView(this).apply {
                text = t.customer
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                text = "#${t.no} · ${StatusUi.fmtCurrency(t.cpoAmount)}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                textSize = 12f
            })
            row.addView(info)
            val markBtn = com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "Mark collected"
                textSize = 11f
                isAllCaps = false
                strokeColor = ContextCompat.getColorStateList(this@MainActivity, R.color.brand_primary)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand_primary))
                setPadding(20, 0, 20, 0)
                setOnClickListener {
                    lifecycleScope.launch { repo.markCpoCollected(t.id) }
                }
            }
            row.addView(markBtn)
            b.cpoList.addView(row)
        }
    }

    // ---------- Recent Activity ----------
    private data class ActivityItem(val title: String, val subtitle: String, val icon: Int, val color: Int, val ts: Long)

    /** Merges real events from tenders/contracts/documents into one feed —
     *  no fabricated data, just the timestamps these tables already track. */
    private fun renderRecentActivity() {
        val items = mutableListOf<ActivityItem>()
        latestTenders.forEach { t ->
            items += ActivityItem("New tender added", t.customer, R.drawable.ic_add_circle, R.color.status_participated, t.createdAt)
            if (t.status == "Won") {
                items += ActivityItem("Tender won", t.customer, R.drawable.ic_trophy, R.color.brand_accent, t.createdAt)
            }
        }
        latestContracts.forEach { c ->
            items += ActivityItem("Contract added", c.customer, R.drawable.ic_clipboard, R.color.status_won, c.createdAt)
        }
        latestDocuments.forEach { d ->
            items += ActivityItem("Document uploaded", d.fileName, R.drawable.ic_description, R.color.status_active, d.uploadDate)
        }

        val recent = items.sortedByDescending { it.ts }.take(6)
        b.activityList.removeAllViews()
        if (recent.isEmpty()) {
            b.activityList.addView(TextView(this).apply {
                text = "No recent activity."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                setPadding(0, 12, 0, 12)
            })
            return
        }
        recent.forEach { a ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                gravity = Gravity.CENTER_VERTICAL
            }
            val iconChip = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (32 * resources.displayMetrics.density).toInt(),
                    (32 * resources.displayMetrics.density).toInt()
                ).apply { marginEnd = (12 * resources.displayMetrics.density).toInt() }
                val c = ContextCompat.getColor(this@MainActivity, a.color)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 10f
                    setColor(android.graphics.Color.argb(38, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)))
                }
            }
            iconChip.addView(View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (16 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt()
                ).apply { gravity = Gravity.CENTER }
                background = ContextCompat.getDrawable(this@MainActivity, a.icon)
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, a.color)
            })
            row.addView(iconChip)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = a.title
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = a.subtitle
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(info)
            row.addView(TextView(this).apply {
                text = relativeTime(a.ts)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                textSize = 11f
            })
            b.activityList.addView(row)
        }
    }

    private fun relativeTime(ts: Long): String {
        val diffMs = System.currentTimeMillis() - ts
        val mins = diffMs / 60000
        return when {
            mins < 1 -> "Just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            else -> "${mins / (60 * 24)}d ago"
        }
    }

    // ---------- Ongoing Contracts ----------
    private fun renderOngoingContracts(contracts: List<Contract>) {
        val ongoing = contracts
            .filter { it.status == "Active" }
            .sortedBy { StatusUi.daysUntil(it.endDate) ?: Int.MAX_VALUE }
            .take(5)

        b.contractsList.removeAllViews()
        if (ongoing.isEmpty()) {
            b.contractsList.addView(TextView(this).apply {
                text = "No ongoing contracts."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                setPadding(0, 12, 0, 12)
            })
            return
        }
        ongoing.forEach { c ->
            val days = StatusUi.daysUntil(c.endDate)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 10)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { startActivity(Intent(this@MainActivity, ContractsActivity::class.java)) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = c.customer
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                text = "#${c.tenderNo} · Next service: ${c.endDate}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                textSize = 12f
            })
            row.addView(info)
            val badge = TextView(this).apply {
                text = if (days != null) "$days" + "d left" else c.status
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(24, 10, 24, 10)
                val color = if (days != null && days <= 14)
                    ContextCompat.getColor(this@MainActivity, R.color.status_pending)
                else
                    ContextCompat.getColor(this@MainActivity, R.color.status_won)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 28f
                    setColor(color)
                }
            }
            row.addView(badge)
            b.contractsList.addView(row)
        }
    }

    // ---------- Navigation ----------
    private fun openDetail(id: Long) {
        val i = Intent(this, TenderDetailActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = NotificationScheduler.buildDeepLinkUri(id)
            putExtra(TenderDetailActivity.EXTRA_TENDER_ID, id)
        }
        startActivity(i)
    }

    private fun launchAddTender(id: Long?) {
        val i = Intent(this, AddTenderActivity::class.java)
        if (id != null) i.putExtra(AddTenderActivity.EXTRA_TENDER_ID, id)
        startActivity(i)
    }
}
