package com.example.videoshrink

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.videoshrink.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(ErrorLogStore.exportText(this).toByteArray()) }
        }.onSuccess {
            Snackbar.make(binding.root, getString(R.string.settings_log_exported), Snackbar.LENGTH_LONG).show()
        }.onFailure { e ->
            ErrorLogStore.add(this, "LogExport", "Export failed", e)
            Snackbar.make(binding.root, getString(R.string.settings_log_export_failed), Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        binding.backButton.setOnClickListener { finish() }
        binding.languageButton.setOnClickListener { showLanguageDialog() }
        binding.presetButton.setOnClickListener { startActivity(android.content.Intent(this, PresetManagerActivity::class.java)) }
        binding.smartThresholdButton.setOnClickListener { showSmartThresholdDialog() }
        binding.smartScanButton.setOnClickListener { startActivity(android.content.Intent(this, SmartScanActivity::class.java)) }
        binding.exportLogButton.setOnClickListener { exportLauncher.launch("yingya-diagnostic-log.txt") }
        binding.clearLogButton.setOnClickListener {
            ErrorLogStore.clear(this)
            Snackbar.make(binding.root, getString(R.string.settings_logs_cleared), Snackbar.LENGTH_SHORT).show()
        }
        binding.onboardingButton.setOnClickListener { startActivity(android.content.Intent(this, OnboardingActivity::class.java)) }
        binding.notificationSwitch.isChecked = SettingsStore.completionNotifications(this)
        binding.notificationSwitch.setOnCheckedChangeListener { _, checked -> SettingsStore.setCompletionNotifications(this, checked) }
        refresh()
    }

    override fun onResume() { super.onResume(); if (::binding.isInitialized) refresh() }

    private fun applyInsets() {
        val top = binding.topArea.paddingTop
        val bottom = binding.scrollContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.topArea.setPadding(binding.topArea.paddingLeft, top + bars.top, binding.topArea.paddingRight, binding.topArea.paddingBottom)
            binding.scrollContent.setPadding(binding.scrollContent.paddingLeft, binding.scrollContent.paddingTop, binding.scrollContent.paddingRight, bottom + bars.bottom)
            insets
        }
    }

    private fun refresh() {
        val tag = SettingsStore.languageTag(this)
        binding.languageValue.text = when (tag) {
            "zh-CN" -> "简体中文"
            "en" -> "English"
            "ja" -> "日本語"
            "ko" -> "한국어"
            "es" -> "Español"
            else -> "跟随系统"
        }
        val preset = PresetStore.defaultPreset(this)
        binding.presetValue.text = "${preset.name} · ${preset.options.targetShortSide}P · ${preset.options.codec.name}"
        binding.smartThresholdValue.text = getString(R.string.settings_threshold_value, SettingsStore.smartMinSavingPercent(this), SettingsStore.smartMinSavingMb(this))
    }

    private fun showLanguageDialog() {
        val labels = arrayOf("跟随系统", "简体中文", "English", "日本語", "한국어", "Español")
        val tags = arrayOf("", "zh-CN", "en", "ja", "ko", "es")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_language)
            .setItems(labels) { _, which ->
                val tag = tags[which]
                SettingsStore.setLanguageTag(this, tag)
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                recreate()
            }.show()
    }

    private fun showSmartThresholdDialog() {
        val options = arrayOf("10% / 10 MB", "15% / 20 MB（推荐）", "20% / 50 MB", "30% / 100 MB")
        val values = arrayOf(10 to 10, 15 to 20, 20 to 50, 30 to 100)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_smart_threshold)
            .setItems(options) { _, which ->
                SettingsStore.setSmartMinSavingPercent(this, values[which].first)
                SettingsStore.setSmartMinSavingMb(this, values[which].second)
                refresh()
            }.show()
    }
}
