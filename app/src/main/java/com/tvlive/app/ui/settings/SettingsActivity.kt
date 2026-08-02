package com.tvlive.app.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvlive.app.R
import com.tvlive.app.TvLiveApp
import com.tvlive.app.databinding.ActivitySettingsBinding
import com.tvlive.app.ui.MainViewModel
import com.tvlive.app.ui.RefreshState
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.itemSourceManager.setOnClickListener {
            startActivity(Intent(this, SourceManagerActivity::class.java))
        }

        binding.itemRefresh.setOnClickListener {
            viewModel.refreshSources()
            Toast.makeText(this, R.string.action_refresh, Toast.LENGTH_SHORT).show()
        }

        binding.itemClearHistory.setOnClickListener { confirmClearHistory() }
        binding.itemAbout.setOnClickListener { showAboutDialog() }

        observeRefreshState()
    }

    private fun observeRefreshState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.refreshState.collect { state ->
                    if (state is RefreshState.Done) {
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(
                                R.string.status_done,
                                state.result.successCount,
                                state.result.totalChannels
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_cache)
            .setMessage(R.string.tab_history)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    TvLiveApp.instance.repository.clearHistory()
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_clear_cache,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_about)
            .setMessage(R.string.settings_about_desc)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
