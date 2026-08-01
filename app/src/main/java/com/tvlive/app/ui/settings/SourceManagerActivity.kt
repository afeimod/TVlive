package com.tvlive.app.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvlive.app.R
import com.tvlive.app.data.model.Source
import com.tvlive.app.databinding.ActivitySourceManagerBinding
import com.tvlive.app.databinding.DialogAddSourceBinding
import com.tvlive.app.ui.MainViewModel
import kotlinx.coroutines.launch

class SourceManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourceManagerBinding
    private val viewModel: MainViewModel by viewModels()

    private val adapter: SourceAdapter = SourceAdapter(
        onToggle = { source, enabled ->
            viewModel.updateSource(source.copy(enabled = enabled))
            Toast.makeText(
                this,
                if (enabled) R.string.source_enabled else R.string.source_disabled,
                Toast.LENGTH_SHORT
            ).show()
        },
        onDelete = { source -> confirmDelete(source) },
        onLongClick = { source -> confirmSetDefault(source) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvSources.layoutManager = LinearLayoutManager(this)
        binding.rvSources.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { showAddSourceDialog() }

        observeData()
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allSources.collect { sources ->
                    adapter.submit(sources)
                }
            }
        }
    }

    private fun showAddSourceDialog() {
        val dialogBinding = DialogAddSourceBinding.inflate(LayoutInflater.from(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.action_add_source)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.source_add) { _, _ ->
                val name = dialogBinding.etSourceName.text.toString().trim()
                val url = dialogBinding.etSourceUrl.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, R.string.status_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!url.startsWith("http", ignoreCase = true)) {
                    Toast.makeText(this, R.string.source_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.addSource(name, url)
                Toast.makeText(this, R.string.source_add, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(source: Source) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage("${source.name}")
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteSource(source)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmSetDefault(source: Source) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_set_default)
            .setMessage("${source.name}")
            .setPositiveButton(R.string.action_set_default) { _, _ ->
                viewModel.setDefaultSource(source.id)
                Toast.makeText(this, R.string.action_set_default, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
