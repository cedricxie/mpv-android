package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.ActivityWebdavBrowserBinding
import `is`.xyz.mpv.databinding.ItemWebdavBinding
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class WebDavBrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebdavBrowserBinding
    private lateinit var store: WebDavConfigStore
    private val executor = Executors.newSingleThreadExecutor()
    private var config: WebDavConfig? = null
    private var client: WebDavClient? = null
    private var currentPath = ""
    private var allEntries: List<WebDavEntry> = emptyList()
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebdavBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setTitle(R.string.webdav_title)
        Utils.handleInsetsAsPadding(binding.root)

        store = WebDavConfigStore(this)
        binding.webdavList.layoutManager = LinearLayoutManager(this)
        binding.webdavList.adapter = EntryAdapter(::openEntry)
        binding.webdavConfigureButton.setOnClickListener { showConfigurationDialog() }
        binding.webdavUpButton.setOnClickListener { navigateUp() }
        onBackPressedDispatcher.addCallback(this) {
            if (!navigateUp()) finish()
        }

        val saved = store.load()
        if (saved == null) showConfigurationDialog(required = true)
        else connect(saved)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun connect(newConfig: WebDavConfig) {
        config = newConfig
        client = WebDavClient(newConfig)
        currentPath = WebDavConfigStore.normalizeRoot(newConfig.rootPath)
        if (newConfig.serverUrl.startsWith("https://", ignoreCase = true) &&
            newConfig.certificateFingerprint == null
        ) {
            probeCertificate(newConfig)
            return
        }
        loadDirectory(currentPath)
    }

    private fun probeCertificate(activeConfig: WebDavConfig) {
        binding.webdavProgress.isVisible = true
        binding.webdavMessage.isVisible = true
        binding.webdavMessage.setTextColor(0xffeeeeee.toInt())
        binding.webdavMessage.text = getString(R.string.webdav_checking_certificate)
        executor.execute {
            try {
                val fingerprint = WebDavClient(activeConfig).probeCertificateFingerprint()
                runOnUiThread {
                    if (!isFinishing) showCertificateDialog(fingerprint)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Certificate probe failed", error)
                runOnUiThread { showConnectionError(error) }
            }
        }
    }

    private fun loadDirectory(path: String) {
        val activeClient = client ?: return
        val generation = ++loadGeneration
        binding.webdavProgress.isVisible = true
        binding.webdavMessage.isVisible = false
        binding.webdavPath.text = path
        binding.webdavUpButton.isEnabled = path != config?.rootPath
        executor.execute {
            try {
                val entries = activeClient.list(path)
                runOnUiThread {
                    if (generation != loadGeneration || isFinishing) return@runOnUiThread
                    currentPath = path
                    allEntries = entries
                    val visibleEntries = entries.filter {
                        it.isDirectory || Utils.MEDIA_EXTENSIONS.contains(
                            it.name.substringAfterLast('.', "").lowercase()
                        )
                    }
                    (binding.webdavList.adapter as EntryAdapter).submit(visibleEntries)
                    binding.webdavProgress.isVisible = false
                    binding.webdavMessage.isVisible = visibleEntries.isEmpty()
                    binding.webdavMessage.setText(R.string.webdav_empty)
                    binding.webdavPath.text = path
                    binding.webdavUpButton.isEnabled = path != config?.rootPath
                }
            } catch (trust: CertificateTrustRequired) {
                runOnUiThread { showCertificateDialog(trust.fingerprint) }
            } catch (error: Exception) {
                Log.e(TAG, "WebDAV directory load failed for $path", error)
                runOnUiThread {
                    if (generation != loadGeneration || isFinishing) return@runOnUiThread
                    showConnectionError(error)
                }
            }
        }
    }

    private fun showConnectionError(error: Exception) {
        binding.webdavProgress.isVisible = false
        binding.webdavMessage.isVisible = true
        binding.webdavMessage.setTextColor(0xffff8a80.toInt())
        binding.webdavMessage.text = getString(
            R.string.webdav_connection_failed,
            error.message ?: error.javaClass.simpleName,
        )
    }

    private fun openEntry(entry: WebDavEntry) {
        if (entry.isDirectory) {
            loadDirectory(entry.path)
            return
        }
        val activeClient = client ?: return
        val baseName = entry.name.substringBeforeLast('.', entry.name)
        fun companion(vararg suffixes: String): WebDavEntry? = suffixes.firstNotNullOfOrNull { suffix ->
            allEntries.firstOrNull { it.name.equals(baseName + suffix, ignoreCase = true) }
        }
        val subtitle = companion(".zh.ass", ".ass", ".zh.srt", ".srt")
        val study = companion(".study.json")
        val intent = Intent(this, MPVActivity::class.java)
            .putExtra("filepath", activeClient.urlForPath(entry.path))
            .putExtra(EXTRA_WEBDAV_AUTHORIZATION, activeClient.authorizationHeader)
            .putExtra(EXTRA_WEBDAV_INSECURE_TLS, config?.serverUrl?.startsWith("https://") == true)
        subtitle?.let { intent.putExtra(EXTRA_WEBDAV_SUBTITLE_URL, activeClient.urlForPath(it.path)) }
        study?.let { intent.putExtra(EXTRA_WEBDAV_STUDY_URL, activeClient.urlForPath(it.path)) }
        startActivity(intent)
    }

    private fun navigateUp(): Boolean {
        val root = config?.rootPath ?: return false
        if (currentPath == root) return false
        val parent = currentPath.trimEnd('/').substringBeforeLast('/', "") + "/"
        loadDirectory(if (parent.startsWith(root)) parent else root)
        return true
    }

    private fun showCertificateDialog(fingerprint: String) {
        binding.webdavProgress.isVisible = false
        binding.webdavMessage.isVisible = false
        AlertDialog.Builder(this)
            .setTitle(R.string.webdav_certificate_title)
            .setMessage(getString(R.string.webdav_certificate_message, fingerprint))
            .setPositiveButton(R.string.webdav_trust) { _, _ ->
                val trusted = config?.copy(certificateFingerprint = fingerprint) ?: return@setPositiveButton
                store.save(trusted)
                connect(trusted)
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showConfigurationDialog(required: Boolean = false) {
        val existing = config ?: store.load()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = Utils.convertDp(this@WebDavBrowserActivity, 20f)
            setPadding(padding, 0, padding, 0)
        }
        fun field(hintResource: Int, value: String, password: Boolean = false): EditText {
            return EditText(this).apply {
                hint = getString(hintResource)
                setText(value)
                isSingleLine = true
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                container.addView(this)
            }
        }
        val server = field(R.string.webdav_server, existing?.serverUrl ?: WebDavConfigStore.DEFAULT_SERVER)
        val root = field(R.string.webdav_root, existing?.rootPath ?: WebDavConfigStore.DEFAULT_ROOT)
        val username = field(R.string.webdav_username, existing?.username ?: WebDavConfigStore.DEFAULT_USERNAME)
        val password = field(R.string.webdav_password, existing?.password ?: "", password = true)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.webdav_configure)
            .setView(container)
            .setPositiveButton(R.string.webdav_save, null)
            .apply {
                if (!required) setNegativeButton(R.string.dialog_cancel, null)
            }
            .setCancelable(!required)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (server.text.isBlank() || root.text.isBlank() || username.text.isBlank() || password.text.isBlank()) {
                    return@setOnClickListener
                }
                val normalizedServer = server.text.toString().trim().trimEnd('/')
                val fingerprint = existing?.certificateFingerprint.takeIf {
                    existing?.serverUrl.equals(normalizedServer, ignoreCase = true)
                }
                val saved = WebDavConfig(
                    serverUrl = normalizedServer,
                    rootPath = WebDavConfigStore.normalizeRoot(root.text.toString()),
                    username = username.text.toString().trim(),
                    password = password.text.toString(),
                    certificateFingerprint = fingerprint,
                )
                store.save(saved)
                dialog.dismiss()
                connect(saved)
            }
        }
        dialog.show()
    }

    private class EntryAdapter(
        private val onClick: (WebDavEntry) -> Unit,
    ) : RecyclerView.Adapter<EntryAdapter.Holder>() {
        private var entries: List<WebDavEntry> = emptyList()

        fun submit(newEntries: List<WebDavEntry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemWebdavBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(entries[position], onClick)
        }

        class Holder(private val binding: ItemWebdavBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(entry: WebDavEntry, onClick: (WebDavEntry) -> Unit) {
                binding.webdavItemIcon.text = if (entry.isDirectory) "📁" else "▶"
                binding.webdavItemName.text = entry.name
                binding.webdavItemDetails.text = when {
                    entry.isDirectory -> ""
                    entry.size == null -> entry.contentType.orEmpty()
                    else -> "%.1f MB".format(entry.size / 1024.0 / 1024.0)
                }
                binding.root.setOnClickListener { onClick(entry) }
            }
        }
    }

    companion object {
        private const val TAG = "MpvStudyWebDav"
    }
}
