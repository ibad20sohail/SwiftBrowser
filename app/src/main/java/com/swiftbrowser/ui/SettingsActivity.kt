package com.swiftbrowser.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.BuildConfig
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.engine.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.StorageController

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = "Settings"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
            setBackgroundResource(R.color.toolbar_bg)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * resources.displayMetrics.density).toInt()))
        val container = FrameLayout(this).apply { id = R.id.content }
        root.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.content, SettingsFragment()).commit()
        }
    }

    fun openInBrowser(url: String) {
        setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_URL, url).putExtra(MainActivity.EXTRA_NEW_TAB, true))
        finish()
    }
}

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        click("site_levels") { showSiteLevels() }
        click("ubo_settings") {
            val url = AdBlock.extension?.metaData?.optionsPageUrl
            if (url != null) (activity as? SettingsActivity)?.openInBrowser(url)
        }
        click("update_lists") {
            AdBlock.updateFilterLists()
            it.summary = "Updating… (this takes a minute in the background)"
        }
        click("clear_data") { clearData() }
        findPreference<Preference>("download_folder")?.summary =
            Downloads.directory().path.replace("/storage/emulated/0", "Phone storage")
        findPreference<Preference>("about")?.summary =
            "Version ${BuildConfig.VERSION_NAME} • Firefox engine (GeckoView) 143 • uBlock Origin ${AdBlock.uboVersion}"
        updateSiteLevelsSummary()
    }

    private fun click(key: String, action: (Preference) -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener {
            action(it)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.DARK_MODE -> Sheets.setDarkMode(SwiftApp.prefs.darkMode)
            Prefs.AD_LEVEL -> AdBlock.setGlobal(SwiftApp.prefs.adLevel)
        }
    }

    private fun updateSiteLevelsSummary() {
        val n = AdBlock.overrides().size
        findPreference<Preference>("site_levels")?.summary =
            if (n == 0) "None. Set one from the shield button while on a site." else "$n ${if (n == 1) "site" else "sites"}"
    }

    private fun showSiteLevels() {
        val ctx = requireContext()
        val entries = AdBlock.overrides().entries.toList()
        if (entries.isEmpty()) {
            MaterialAlertDialogBuilder(ctx).setMessage("No sites have their own level yet.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Tap a site to reset it")
            .setItems(entries.map { "${it.key} — ${it.value.label}" }.toTypedArray()) { _, which ->
                AdBlock.setOverride(entries[which].key, null)
                updateSiteLevelsSummary()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun clearData() {
        val ctx = requireContext()
        val profile = Profiles.current()
        val labels = arrayOf("History", "Cookies and site data (signs you out)", "Cached images and files")
        val checked = booleanArrayOf(true, false, true)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Clear data • ${profile.name}")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Clear") { _, _ ->
                if (checked[1]) Engine.runtime.storageController.clearDataForSessionContext(Profiles.contextId(profile.id))
                if (checked[2]) Engine.runtime.storageController.clearData(StorageController.ClearFlags.ALL_CACHES)
                if (checked[0]) {
                    lifecycleScope.launch { withContext(Dispatchers.IO) { SwiftApp.db.clearHistory(profile.id) } }
                }
                android.widget.Toast.makeText(ctx, "Cleared", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
