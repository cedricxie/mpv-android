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
        binding.webdavSwitchButton.setOnClickListener { showProfileSwitcher() }
        binding.webdavConfigureButton.setOnClickListener { showProfileManager() }
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
        ++loadGeneration
        config = newConfig
        client = WebDavClient(newConfig)
        currentPath = WebDavConfigStore.normalizeRoot(newConfig.rootPath)
        binding.webdavSwitchButton.text = newConfig.name
        binding.webdavSwitchButton.contentDescription = getString(R.string.webdav_switch)
        if (newConfig.certificateFingerprint == null) {
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
                val certificate = WebDavClient(activeConfig).probeCertificate()
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    showCertificateDialog(certificate)
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
                runOnUiThread { config?.let(::probeCertificate) }
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
        val byName = allEntries.associateBy { it.name.lowercase() }
        val companions = StudyDocumentResolver.findNames(byName.keys, baseName)
        val intent = Intent(this, MPVActivity::class.java)
            .putExtra("filepath", activeClient.urlForPath(entry.path))
        companions.primarySubtitle?.let { name ->
            intent.putExtra(
                EXTRA_WEBDAV_SUBTITLE_URL,
                activeClient.urlForPath(byName.getValue(name.lowercase()).path),
            )
        }
        companions.secondarySubtitle?.let { name ->
            intent.putExtra(
                EXTRA_WEBDAV_SECONDARY_SUBTITLE_URL,
                activeClient.urlForPath(byName.getValue(name.lowercase()).path),
            )
        }
        companions.studyData?.let { name ->
            intent.putExtra(
                EXTRA_WEBDAV_STUDY_URL,
                activeClient.urlForPath(byName.getValue(name.lowercase()).path),
            )
        }
        startActivity(intent)
    }

    private fun navigateUp(): Boolean {
        val root = config?.rootPath ?: return false
        if (currentPath == root) return false
        val parent = currentPath.trimEnd('/').substringBeforeLast('/', "") + "/"
        loadDirectory(if (parent.startsWith(root)) parent else root)
        return true
    }

    private fun showCertificateDialog(certificate: WebDavServerCertificate) {
        binding.webdavProgress.isVisible = false
        binding.webdavMessage.isVisible = false
        AlertDialog.Builder(this)
            .setTitle(R.string.webdav_certificate_title)
            .setMessage(getString(R.string.webdav_certificate_message, certificate.fingerprint))
            .setPositiveButton(R.string.webdav_trust) { _, _ ->
                val trusted = config?.copy(
                    certificateFingerprint = certificate.fingerprint,
                ) ?: return@setPositiveButton
                connect(store.save(trusted))
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showProfileSwitcher() {
        val profiles = store.list()
        if (profiles.isEmpty()) {
            showConfigurationDialog(required = true)
            return
        }
        val currentId = config?.id
        val checked = profiles.indexOfFirst { it.id == currentId }
        AlertDialog.Builder(this)
            .setTitle(R.string.webdav_switch)
            .setSingleChoiceItems(profiles.map { it.name }.toTypedArray(), checked) { dialog, which ->
                store.select(profiles[which].id)?.let(::connect)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showProfileManager() {
        val profiles = store.list()
        val labels = profiles.map { "✎  ${it.name}" } + getString(R.string.webdav_add)
        AlertDialog.Builder(this)
            .setTitle(R.string.webdav_profiles)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == profiles.size) showConfigurationDialog(existing = null)
                else showConfigurationDialog(existing = profiles[which])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showConfigurationDialog(
        existing: WebDavConfig? = config ?: store.load(),
        required: Boolean = false,
    ) {
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
        val name = field(
            R.string.webdav_profile_name,
            existing?.name ?: "",
        )
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
                if (existing != null && !required) {
                    setNeutralButton(R.string.webdav_delete, null)
                }
            }
            .setCancelable(!required)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (
                    name.text.isBlank() ||
                    server.text.isBlank() ||
                    root.text.isBlank() ||
                    username.text.isBlank() ||
                    password.text.isBlank()
                ) {
                    return@setOnClickListener
                }
                val normalizedServer = server.text.toString().trim().trimEnd('/')
                if (!normalizedServer.startsWith("https://", ignoreCase = true)) {
                    server.error = getString(R.string.webdav_https_required)
                    return@setOnClickListener
                }
                val fingerprint = existing?.certificateFingerprint.takeIf {
                    existing?.serverUrl.equals(normalizedServer, ignoreCase = true)
                }
                val saved = WebDavConfig(
                    id = existing?.id.orEmpty(),
                    name = name.text.toString().trim(),
                    serverUrl = normalizedServer,
                    rootPath = WebDavConfigStore.normalizeRoot(root.text.toString()),
                    username = username.text.toString().trim(),
                    password = password.text.toString(),
                    certificateFingerprint = fingerprint,
                )
                dialog.dismiss()
                connect(store.save(saved))
            }
            if (existing != null && !required) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    confirmDeleteProfile(dialog, existing)
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteProfile(editorDialog: AlertDialog, profile: WebDavConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.webdav_delete_title)
            .setMessage(getString(R.string.webdav_delete_message, profile.name))
            .setPositiveButton(R.string.webdav_delete) { _, _ ->
                editorDialog.dismiss()
                val next = store.delete(profile.id)
                if (next == null) {
                    config = null
                    client = null
                    binding.webdavSwitchButton.setText(R.string.webdav_switch)
                    showConfigurationDialog(existing = null, required = true)
                } else {
                    connect(next)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
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
