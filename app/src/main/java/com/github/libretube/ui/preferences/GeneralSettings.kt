package com.github.libretube.ui.preferences

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.github.libretube.BuildConfig
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.LocaleHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.dialogs.RequireRestartDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale

class GeneralSettings : BasePreferenceFragment() {

    private val pickDownloadFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            onCustomDownloadFolderSelected(uri)
        }

    // the all files access settings screen doesn't return a result code, so check the
    // permission state once the user navigates back to the app
    private val requestAllFilesAccess =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val context = context ?: return@registerForActivityResult
            if (DownloadHelper.hasAllFilesAccess(context)) {
                pickDownloadFolder.launch(null)
            } else {
                context.toastFromMainThread(R.string.download_location_permission_denied)
            }
        }

    private val requestLegacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pickDownloadFolder.launch(null)
            } else {
                context?.toastFromMainThread(R.string.download_location_permission_denied)
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.general_settings, rootKey)

        findPreference<Preference>(PreferenceKeys.DOWNLOAD_LOCATION)?.let { pref ->
            updateDownloadLocationSummary(pref)
            pref.setOnPreferenceClickListener {
                showDownloadLocationDialog()
                true
            }
        }

        val language = findPreference<ListPreference>("language")
        if (!LocaleHelper.isPerAppLocaleSettingSupported()) {
            language?.setOnPreferenceChangeListener { _, _ ->
                RequireRestartDialog().show(
                    childFragmentManager,
                    RequireRestartDialog::class.java.name
                )
                true
            }
            val languages = requireContext().resources.getStringArray(R.array.languageCodes)
                .map { code ->
                    val locale = LocaleHelper.getLocaleFromAndroidCode(code)

                    // each language's name is displayed in its own language,
                    // e.g. 'de': 'Deutsch', 'fr': 'Francais', ...
                    locale.toString() to locale.getDisplayName(locale)
                }.sortedBy { it.second.lowercase() }
            language?.entries =
                arrayOf(requireContext().getString(R.string.systemLanguage)) + languages.map { it.second }
            language?.entryValues = arrayOf("sys") + languages.map { it.first }
        } else {
            // on newer Android versions, the language is set through Android settings

            // set displayed current settings value (i.e. current app language)
            val currentLocale = Locale.getDefault()
            language?.entries = arrayOf(currentLocale.displayLanguage)
            language?.entryValues = arrayOf(currentLocale.isO3Country)
            language?.value = currentLocale.isO3Country

            // open Android settings for per-app language preference for the app
            language?.setOnPreferenceClickListener { _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
                            .setData(Uri.fromParts("package", BuildConfig.APPLICATION_ID, null))
                    )
                } catch (e: Exception) {
                    context?.toastFromMainThread("Failed to open per-app language settings: ${e.message}")
                }
                true
            }
        }

        val autoRotation = findPreference<ListPreference>(PreferenceKeys.ORIENTATION)
        autoRotation?.setOnPreferenceChangeListener { _, _ ->
            RequireRestartDialog().show(childFragmentManager, RequireRestartDialog::class.java.name)
            true
        }

        val resetSettings = findPreference<Preference>(PreferenceKeys.RESET_SETTINGS)
        resetSettings?.setOnPreferenceClickListener {
            showResetDialog()
            true
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == "language" && LocaleHelper.isPerAppLocaleSettingSupported()) return

        super.onDisplayPreferenceDialog(preference)
    }

    private fun updateDownloadLocationSummary(pref: Preference? = null) {
        val preference = pref ?: findPreference(PreferenceKeys.DOWNLOAD_LOCATION) ?: return
        preference.summary = DownloadHelper.getDownloadBaseDir(requireContext()).absolutePath
    }

    private fun setDownloadLocation(path: String) {
        PreferenceHelper.putString(PreferenceKeys.DOWNLOAD_LOCATION, path)
        updateDownloadLocationSummary()
        context?.toastFromMainThread(R.string.download_location_existing_info)
    }

    private fun showDownloadLocationDialog() {
        val context = requireContext()
        val defaultDir = DownloadHelper.getDefaultDownloadBaseDir(context)

        // app specific directories on other storage volumes (e.g. SD cards), these
        // don't require any additional permissions
        val externalVolumeDirs = context.getExternalFilesDirs(null)
            .filterNotNull()
            .filter { it.absolutePath != defaultDir.absolutePath }

        val currentPath = DownloadHelper.getCustomDownloadBaseDir()?.absolutePath.orEmpty()

        val entries = mutableListOf<String>()
        val paths = mutableListOf<String?>()

        entries.add(context.getString(R.string.download_location_default) + "\n" + defaultDir.absolutePath)
        paths.add("")

        externalVolumeDirs.forEachIndexed { index, dir ->
            entries.add(
                context.getString(R.string.download_location_external_volume, index + 1) +
                    "\n" + dir.absolutePath
            )
            paths.add(dir.absolutePath)
        }

        val currentIsCustomFolder = currentPath.isNotEmpty() && currentPath !in paths
        entries.add(
            context.getString(R.string.download_location_custom) +
                if (currentIsCustomFolder) "\n" + currentPath else ""
        )
        // null indicates that a custom folder has to be picked
        paths.add(null)

        val checkedIndex = if (currentIsCustomFolder) {
            paths.lastIndex
        } else {
            paths.indexOf(currentPath).coerceAtLeast(0)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.download_location)
            .setSingleChoiceItems(entries.toTypedArray(), checkedIndex) { dialog, which ->
                dialog.dismiss()
                val path = paths[which]
                if (path != null) {
                    setDownloadLocation(path)
                } else {
                    pickCustomDownloadFolder()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickCustomDownloadFolder() {
        val context = requireContext()
        if (DownloadHelper.hasAllFilesAccess(context)) {
            pickDownloadFolder.launch(null)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestLegacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.download_location_permission_title)
            .setMessage(R.string.download_location_permission_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.okay) { _, _ ->
                val packageUri = Uri.fromParts("package", BuildConfig.APPLICATION_ID, null)
                try {
                    requestAllFilesAccess.launch(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
                    )
                } catch (e: Exception) {
                    runCatching {
                        requestAllFilesAccess.launch(
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        )
                    }
                }
            }
            .show()
    }

    private fun onCustomDownloadFolderSelected(uri: Uri) {
        val context = requireContext()
        val path = DownloadHelper.treeUriToFilePath(context, uri)
        if (path == null) {
            context.toastFromMainThread(R.string.download_location_unsupported_folder)
            return
        }

        val folder = File(path)
        val isWritable = runCatching {
            folder.mkdirs()
            folder.isDirectory && folder.canWrite()
        }.getOrDefault(false)
        if (!isWritable) {
            context.toastFromMainThread(R.string.download_location_not_writable)
            return
        }

        setDownloadLocation(folder.absolutePath)
    }

    private fun showResetDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reset)
            .setMessage(R.string.reset_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset) { _, _ ->
                // clear default preferences
                PreferenceHelper.clearPreferences()

                // clear login token
                PreferenceHelper.setToken("")

                ActivityCompat.recreate(requireActivity())
            }
            .show()
    }
}
