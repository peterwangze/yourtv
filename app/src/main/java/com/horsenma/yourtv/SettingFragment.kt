package com.horsenma.yourtv


import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.horsenma.yourtv.ModalFragment.Companion.KEY_URL
import com.horsenma.yourtv.SimpleServer.Companion.PORT
import com.horsenma.yourtv.databinding.SettingBinding
import kotlin.math.max
import kotlin.math.min
import android.view.KeyEvent
import android.content.Intent
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.app.Dialog
import android.content.ActivityNotFoundException
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.annotation.RequiresApi


@Suppress("DEPRECATION")
class SettingFragment : Fragment() {

    private var _binding: SettingBinding? = null
    private val binding get() = _binding!!

    private lateinit var uri: Uri
    private lateinit var updateManager: UpdateManager
    private var server = "http://${PortUtil.lan()}:$PORT"
    private lateinit var viewModel: MainViewModel
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 新增：复用触摸屏检测逻辑
    private fun isTouchScreenDevice(context: Context): Boolean {
        val packageManager = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTv = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireActivity()
        val mainActivity = (activity as MainActivity)
        val application = context.applicationContext as YourTVApplication
        val imageHelper = application.imageHelper
        viewModel = ViewModelProvider(context)[MainViewModel::class.java]
        binding.switchDisplaySeconds.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDisplaySeconds(isChecked)
        }

        // 初始化控件（保持原有逻辑）
        binding.versionName.text = "v${context.appVersionName}"
        binding.version.text = requireContext().getString(R.string.project_address)
        binding.version.isFocusable = true
        binding.version.isFocusableInTouchMode = true
        binding.version.setOnClickListener {
            val url = requireContext().getString(R.string.project_github_address)
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No browser found to open URL: $url", e)
                R.string.no_browser_found.showToast()
                binding.version.requestFocus()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open URL: $url", e)
                R.string.no_browser_found.showToast()
                binding.version.requestFocus()
            }
        }
        binding.version.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.version.background =
                    ContextCompat.getColor(context, R.color.focus).toDrawable()
                binding.version.setTextColor(ContextCompat.getColor(context, R.color.white))
            } else {
                binding.version.background =
                    ContextCompat.getColor(context, R.color.description_blur).toDrawable()
                binding.version.setTextColor(ContextCompat.getColor(context, R.color.blur))
            }
        }

        val switchChannelReversal = _binding?.switchChannelReversal
        switchChannelReversal?.isChecked = SP.channelReversal
        switchChannelReversal?.setOnCheckedChangeListener { _, isChecked ->
            SP.channelReversal = isChecked
            mainActivity.settingActive()
        }

        val switchChannelNum = _binding?.switchChannelNum
        switchChannelNum?.isChecked = SP.channelNum
        switchChannelNum?.setOnCheckedChangeListener { _, isChecked ->
            SP.channelNum = isChecked
            mainActivity.settingActive()
        }

        val switchTime = _binding?.switchTime
        switchTime?.isChecked = SP.time
        switchTime?.setOnCheckedChangeListener { _, isChecked ->
            SP.time = isChecked
            mainActivity.settingActive()
        }

        val switchBootStartup = _binding?.switchBootStartup
        switchBootStartup?.isChecked = SP.bootStartup
        switchBootStartup?.setOnCheckedChangeListener { _, isChecked ->
            SP.bootStartup = isChecked
            mainActivity.settingActive()
        }

        val switchRepeatInfo = _binding?.switchRepeatInfo
        switchRepeatInfo?.isChecked = SP.repeatInfo
        switchRepeatInfo?.setOnCheckedChangeListener { _, isChecked ->
            SP.repeatInfo = isChecked
            mainActivity.settingActive()
        }

        val switchDefaultLike = _binding?.switchDefaultLike
        switchDefaultLike?.isChecked = SP.defaultLike
        switchDefaultLike?.setOnCheckedChangeListener { _, isChecked ->
            SP.defaultLike = isChecked
            mainActivity.settingActive()
        }

        val switchShowAllChannels = _binding?.switchShowAllChannels
        switchShowAllChannels?.isChecked = SP.showAllChannels

        val switchCompactMenu = _binding?.switchCompactMenu
        switchCompactMenu?.isChecked = SP.compactMenu
        switchCompactMenu?.setOnCheckedChangeListener { _, isChecked ->
            SP.compactMenu = isChecked
            mainActivity.updateMenuSize()
            mainActivity.settingActive()
        }

        val switchDisplaySeconds = _binding?.switchDisplaySeconds
        switchDisplaySeconds?.isChecked = SP.displaySeconds

        val switchSoftDecode = _binding?.switchSoftDecode
        switchSoftDecode?.isChecked = SP.softDecode
        switchSoftDecode?.setOnCheckedChangeListener { _, isChecked ->
            SP.softDecode = isChecked
            mainActivity.switchSoftDecode()
            mainActivity.settingActive()
        }

        val switchAutoSwitchSource = _binding?.switchAutoSwitchSource
        switchAutoSwitchSource?.isChecked = SP.autoSwitchSource
        switchAutoSwitchSource?.setOnCheckedChangeListener { _, isChecked ->
            SP.autoSwitchSource = isChecked
            mainActivity.settingActive()
        }

        val switchFastZap = _binding?.switchFastZap
        switchFastZap?.isChecked = SP.fastZap
        switchFastZap?.setOnCheckedChangeListener { _, isChecked ->
            SP.fastZap = isChecked
            mainActivity.settingActive()
        }

        val isTouchScreen = isTouchScreenDevice(context)
        val switchEnableScreenOffAudio = _binding?.switchEnableScreenOffAudio
        switchEnableScreenOffAudio?.isChecked = SP.enableScreenOffAudio
        switchEnableScreenOffAudio?.visibility = if (isTouchScreen) View.VISIBLE else View.GONE
        switchEnableScreenOffAudio?.setOnCheckedChangeListener { _, isChecked ->
            SP.enableScreenOffAudio = isChecked
            mainActivity.settingActive()
        }

        val switchFullScreenMode = _binding?.switchFullScreenMode
        switchFullScreenMode?.isChecked = SP.fullScreenMode
        switchFullScreenMode?.setOnCheckedChangeListener { _, isChecked ->
            SP.fullScreenMode = isChecked
            mainActivity.updateFullScreenMode(isChecked)
            (context.applicationContext as YourTVApplication).toggleFullScreenMode(isChecked) // 新增
            mainActivity.settingActive()
        }

        val switchEnableWebviewType = _binding?.switchEnableWebviewType
        switchEnableWebviewType?.isChecked = SP.enableWebviewType
        switchEnableWebviewType?.visibility =  View.VISIBLE
        switchEnableWebviewType?.setOnCheckedChangeListener { _, isChecked ->
            SP.enableWebviewType = isChecked
            (activity as MainActivity).handleWebviewTypeSwitch(isChecked)
            mainActivity.settingActive()
        }

        val switchShowSourceButton = _binding?.switchShowSourceButton
        switchShowSourceButton?.isChecked = SP.showSourceButton
        // 仅在触摸屏设备上显示开关
        switchShowSourceButton?.visibility = if (isTouchScreen) View.VISIBLE else View.GONE
        switchShowSourceButton?.setOnCheckedChangeListener { _, isChecked ->
            SP.showSourceButton = isChecked
            mainActivity.settingActive()
            // 通知 PlayerFragment 更新 btn_source 可见性
            if (mainActivity.playerFragment.isAdded) {
                mainActivity.playerFragment.setSourceButtonVisibility(true)
            }
        }

        val switchExit = _binding?.switchExit
        switchExit?.isChecked = false // Default state for exit switch
        switchExit?.visibility = View.VISIBLE
        switchExit?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requireActivity().finishAffinity()
            }
        }

        binding.remoteSettings.setOnClickListener {
            val imageModalFragment = ModalFragment()
            val args = Bundle()
            args.putString(KEY_URL, server)
            imageModalFragment.arguments = args

            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        binding.checkVersion.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    startActivityForResult(intent, REQUEST_UNKNOWN_APP_SOURCES)
                    Toast.makeText(context, R.string.enable_unknown_sources, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open unknown sources settings: ${e.message}")
                    R.string.install_failed.showToast() // 修复
                }
            } else {
                try {
                    updateManager.checkAndUpdate(isAutoCheck = false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check update: ${e.message}")
                    Toast.makeText(context, R.string.update_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.checkVersion.setOnLongClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    startActivityForResult(intent, REQUEST_UNKNOWN_APP_SOURCES)
                    R.string.install_failed.showToast() // 修复
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open unknown sources settings: ${e.message}")
                    Toast.makeText(context, R.string.install_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    updateManager.checkAndUpdate(isAutoCheck = false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check update: ${e.message}")
                    Toast.makeText(context, R.string.update_failed, Toast.LENGTH_SHORT).show()
                }
            }
            true // 消费长按事件，阻止 MenuFragment
        }

        binding.confirmConfig.setOnClickListener {
            val sourcesFragment = SourcesFragment()
            sourcesFragment.show(requireFragmentManager(), SourcesFragment.TAG)
            mainActivity.settingActive()
        }

        binding.appreciate.setOnClickListener {
            val imageModalFragment = ModalFragment()

            val args = Bundle()
            args.putInt(ModalFragment.KEY_DRAWABLE_ID, R.drawable.appreciate)
            imageModalFragment.arguments = args

            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        // 手动刷新节目单（跳过自动冷却，给出成功/失败反馈）
        binding.updateEpg.setOnClickListener {
            viewModel.updateEPG(force = true)
            mainActivity.settingActive()
        }

        // 强制刷新当前直播源（跳过 24h 缓存重新下载）
        binding.refreshSource.setOnClickListener {
            viewModel.refreshActiveSource()
            mainActivity.settingActive()
        }

        binding.setting.setOnClickListener {
            hideSelf()
            (activity as? MainActivity)?.settingActive() // 新增
        }

        binding.verifyUser.setOnClickListener {
            showVerificationDialog()
        }

        val txtTextSize = application.px2PxFont(binding.versionName.textSize)

        binding.content.layoutParams.width =
            application.px2Px(binding.content.layoutParams.width)
        binding.content.setPadding(
            application.px2Px(binding.content.paddingLeft),
            application.px2Px(binding.content.paddingTop),
            application.px2Px(binding.content.paddingRight),
            application.px2Px(binding.content.paddingBottom)
        )

        binding.name.textSize = application.px2PxFont(binding.name.textSize)
        binding.version.textSize = txtTextSize
        val layoutParamsVersion = binding.version.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsVersion.topMargin = application.px2Px(binding.version.marginTop)
        layoutParamsVersion.bottomMargin = application.px2Px(binding.version.marginBottom)
        binding.version.layoutParams = layoutParamsVersion

        val btnWidth = application.px2Px(binding.confirmConfig.layoutParams.width)

        val btnLayoutParams = binding.verifyUser.layoutParams as ViewGroup.MarginLayoutParams
        btnLayoutParams.marginEnd = application.px2Px(binding.verifyUser.marginEnd)

        binding.versionName.textSize = txtTextSize

        for (i in listOf(
            binding.remoteSettings,
            binding.confirmConfig,
            binding.clear,
            binding.checkVersion,
            binding.verifyUser,
            binding.appreciate,
        )) {
            i.layoutParams.width = btnWidth
            i.textSize = txtTextSize
            i.layoutParams = btnLayoutParams
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    //i.background = ContextCompat.getColor(context,R.color.focus).toDrawable()
                    i.setTextColor(ContextCompat.getColor(context,R.color.white))
                } else {
                    i.setTextColor(ContextCompat.getColor(context,R.color.blur))
                }
            }
        }

        val textSizeSwitch = application.px2PxFont(binding.switchChannelReversal.textSize)

        val layoutParamsSwitch =
            binding.switchChannelReversal.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsSwitch.topMargin =
            application.px2Px(binding.switchChannelReversal.marginTop)

        for (i in listOf(
            binding.switchEnableWebviewType,
            binding.switchChannelReversal,
            binding.switchChannelNum,
            binding.switchTime,
            binding.switchBootStartup,
            binding.switchRepeatInfo,
            binding.switchDefaultLike,
            binding.switchShowAllChannels,
            binding.switchCompactMenu,
            binding.switchDisplaySeconds,
            binding.switchSoftDecode,
            binding.switchAutoSwitchSource,
            binding.switchFastZap,
            binding.switchShowSourceButton,
            binding.switchEnableScreenOffAudio,
            binding.switchFullScreenMode,
            binding.switchExit,
        )) {
            i.textSize = textSizeSwitch
            i.layoutParams = layoutParamsSwitch
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.focus
                        )
                    )
                } else {
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.title_blur
                        )
                    )
                }
            }
        }

        val activity = requireActivity()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
        } else {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
        }
        updateManager = UpdateManager(requireActivity(), versionCode)

        binding.clear.setOnClickListener {
            // 顯示確認對話框
            val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage(R.string.confirm_reset_settings)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    Log.d(TAG, "Clearing non-playback settings")

                    // 重置非播放相關配置
                    SP.channelNum = SP.DEFAULT_CHANNEL_NUM
                    SP.channelReversal = SP.DEFAULT_CHANNEL_REVERSAL
                    SP.time = SP.DEFAULT_TIME
                    SP.bootStartup = SP.DEFAULT_BOOT_STARTUP
                    SP.repeatInfo = SP.DEFAULT_REPEAT_INFO
                    SP.defaultLike = false
                    SP.showAllChannels = SP.DEFAULT_SHOW_ALL_CHANNELS
                    SP.compactMenu = SP.DEFAULT_COMPACT_MENU
                    SP.displaySeconds = SP.DEFAULT_DISPLAY_SECONDS
                    SP.autoSwitchSource = SP.DEFAULT_AUTO_SWITCH_SOURCE
                    SP.showSourceButton = SP.DEFAULT_SHOW_SOURCE_BUTTON
                    SP.enableWebviewType = SP.DEFAULT_ENABLE_WEBVIEW_TYPE
                    SP.proxy = SP.DEFAULT_PROXY
                    SP.fullScreenMode = SP.DEFAULT_FULL_SCREEN_MODE
                    SP.epg = SP.DEFAULT_EPG
                    SP.deleteLike()

                    // 更新 ViewModel 和 UI
                    viewModel.setDisplaySeconds(SP.DEFAULT_DISPLAY_SECONDS)
                    viewModel.updateEPG()
                    viewModel.groupModel.setChange() // 更新菜單顯示
                    (activity as? MainActivity)?.updateMenuSize() // 更新菜單樣式
                    (activity as? MainActivity)?.settingActive()

                    R.string.config_restored.showToast()
                    Log.d(TAG, "Non-playback settings reset completed")
                }
                .setNegativeButton(R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                    (activity as? MainActivity)?.settingActive() // 新增
                    Log.d(TAG, "Clear settings cancelled")
                }
                .setCancelable(true)
                .create()

            // 設置對話框樣式、尺寸和焦點
            dialog.setOnShowListener {
                // 設置背景黑色 80% 透明
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#33000000")))

                // 設置尺寸（寬度 80% 屏幕，高度 25% 屏幕）
                val displayMetrics = resources.displayMetrics
                val dialogWidth = (displayMetrics.widthPixels * 0.30).toInt()
                val dialogHeight = (displayMetrics.heightPixels * 0.25).toInt()
                dialog.window?.setLayout(dialogWidth, dialogHeight)

                // 獲取按鈕
                val positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                val negativeButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)

                // 設置焦點變化監聽器
                positiveButton?.setOnFocusChangeListener { _, hasFocus ->
                    positiveButton.setTextColor(ContextCompat.getColor(
                        requireContext(),
                        if (hasFocus) R.color.focus else R.color.blur
                    ))
                    negativeButton?.setTextColor(ContextCompat.getColor(
                        requireContext(),
                        if (hasFocus) R.color.blur else R.color.focus
                    ))
                    Log.d(TAG, "Positive button focus: $hasFocus")
                }
                negativeButton?.setOnFocusChangeListener { _, hasFocus ->
                    negativeButton.setTextColor(ContextCompat.getColor(
                        requireContext(),
                        if (hasFocus) R.color.focus else R.color.blur
                    ))
                    positiveButton?.setTextColor(ContextCompat.getColor(
                        requireContext(),
                        if (hasFocus) R.color.blur else R.color.focus
                    ))
                    Log.d(TAG, "Negative button focus: $hasFocus")
                }

                // 初始焦點和顏色
                positiveButton?.let { button ->
                    button.setTextColor(ContextCompat.getColor(requireContext(), R.color.focus))
                    button.requestFocus()
                }
                negativeButton?.setTextColor(ContextCompat.getColor(requireContext(), R.color.blur))
                Log.d(TAG, "Positive button focused: ${positiveButton?.isFocused}")
            }
            dialog.show()
            (activity as? MainActivity)?.settingActive() // 新增
        }

        binding.switchShowAllChannels.setOnCheckedChangeListener { _, isChecked ->
            SP.showAllChannels = isChecked
            viewModel.groupModel.setChange()
            mainActivity.settingActive()
        }

        // 添加按键事件调试
        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true

        // 统一焦点管理
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        // 确保 name 可聚焦并添加焦点监听
        binding.name.isFocusable = true
        binding.name.isFocusableInTouchMode = true
        binding.name.setOnFocusChangeListener { _, hasFocus ->
            binding.name.setTextColor(ContextCompat.getColor(context, if (hasFocus) R.color.focus else R.color.title_blur))
            Log.d(TAG, "name focus changed: hasFocus=$hasFocus")
        }

        view.post {
            binding.remoteSettings.isFocusable = true
            binding.remoteSettings.isFocusableInTouchMode = true
            binding.remoteSettings.requestFocus()
            binding.remoteSettings.onFocusChangeListener?.onFocusChange(binding.remoteSettings, true)
            Log.d(TAG, "Focus requested on remoteSettings")
        }

        // 添加触摸监听，重置计时器
        binding.root.setOnTouchListener { _, _ ->
            (activity as? MainActivity)?.settingActive()
            false
        }

        // 更新按键监听
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                (activity as? MainActivity)?.settingActive() // 重置计时器
                Log.d(TAG, "Key pressed: $keyCode (Left: 21, Right: 22)")
            }
            false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_UNKNOWN_APP_SOURCES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && requireContext().packageManager.canRequestPackageInstalls()) {
                try {
                    updateManager.checkAndUpdate(isAutoCheck = false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check update after permission: ${e.message}")
                    Toast.makeText(requireContext(), R.string.update_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), R.string.enable_unknown_sources, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmChannel() {
        SP.channel =
            min(max(SP.channel, 0), viewModel.groupModel.getAllList()!!.size())

        (activity as MainActivity).settingActive()
    }

    fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
        (activity as MainActivity).showTimeFragment()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            view?.post {
                binding.remoteSettings.isFocusable = true
                binding.remoteSettings.isFocusableInTouchMode = true
                binding.remoteSettings.requestFocus()
                binding.remoteSettings.onFocusChangeListener?.onFocusChange(binding.remoteSettings, true)
                Log.d(TAG, "onHiddenChanged: Focus requested on remoteSettings")
            }
        }
    }

    private fun checkAndAddPermission(context: Context, permission: String, permissionsList: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(permission)
        }
    }

    private fun requestInstallPermissions() {
        val context = requireContext()
        val permissionsList = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            permissionsList.add(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        }

        checkAndAddPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, permissionsList)
        checkAndAddPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE, permissionsList)

        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsList.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            // 权限已授予，直接检查更新
            updateManager.checkAndUpdate(isAutoCheck = false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_REQUEST_CODE -> {
                var allPermissionsGranted = true
                for (result in grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allPermissionsGranted = false
                        break
                    }
                }
                if (!allPermissionsGranted) {
                    Toast.makeText(requireContext(), R.string.authorization_failed, Toast.LENGTH_SHORT).show()
                    Log.w(TAG, "Permissions denied for requestCode=$PERMISSIONS_REQUEST_CODE")
                } else {
                    Log.i(TAG, "All permissions granted for requestCode=$PERMISSIONS_REQUEST_CODE")
                }
            }
            100 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "WRITE_EXTERNAL_STORAGE permission granted")
                    try {
                        updateManager.checkAndUpdate(isAutoCheck = false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to check update after permission granted: ${e.message}")
                        Toast.makeText(requireContext(), R.string.update_failed, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.w(TAG, "WRITE_EXTERNAL_STORAGE permission denied")
                    Toast.makeText(requireContext(), R.string.grant_storage_permissions, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showVerificationDialog() {
        val mainActivity = activity as? MainActivity
        val dialog = Dialog(requireContext()).apply {
            setContentView(R.layout.loading)
            setCancelable(true)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val callback = object : MainActivity.VerificationCallback {
            override fun onKeyConfirmed(key: String) {}
            override fun onSkip() {}
            override fun onCompleted() {
                hideSelf()
                mainActivity?.settingActive()
            }
        }

        dialog.setOnShowListener {
            dialog.findViewById<View>(R.id.loading)?.setOnTouchListener { _, _ ->
                mainActivity?.settingActive()
                false
            }
            dialog.findViewById<View>(R.id.confirm_button)?.setOnClickListener {
                val handler = UserVerificationHandler(mainActivity!!, UserInfoManager, viewModel)
                val key = handler.getKeyInputText()?.trim() ?: ""
                if (key.isNotEmpty() && key.matches("[0-9A-Z]{1,20}".toRegex())) {
                    handler.triggerConfirm(key, dialog, callback)
                } else {
                    handler.showErrorText("測試碼格式錯，請重新輸入。")
                    handler.requestKeyInputFocus()
                }
                mainActivity?.settingActive()
            }
            dialog.findViewById<View>(R.id.skip_button)?.setOnClickListener {
                UserVerificationHandler(mainActivity!!, UserInfoManager, viewModel).triggerSkip(dialog, callback)
                mainActivity?.settingActive()
            }
        }

        // Show dialog before handleUserVerification
        try {
            dialog.show()
            Log.d(TAG, "showVerificationDialog: Dialog shown")
        } catch (e: Exception) {
            Log.e(TAG, "showVerificationDialog: Failed to show dialog: ${e.message}", e)
            R.string.verify_user_error.showToast()
            return
        }

        // Use UserVerificationHandler
        mainActivity?.let {
            val handler = UserVerificationHandler(it, UserInfoManager, viewModel)
            handler.handleUserVerification(dialog, callback)
        } ?: run {
            Log.e(TAG, "MainActivity not available for verification")
            R.string.verify_user_error.showToast()
            dialog.dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.postDelayed({
            updateManager.destroy()
            Log.d(TAG, "UpdateManager destroyed after delay")
        }, 5000)
        _binding = null
    }

    companion object {
        const val TAG = "SettingFragment"
        const val PERMISSIONS_REQUEST_CODE = 1
        const val REQUEST_UNKNOWN_APP_SOURCES = 2
    }

    private fun Int.showToast() {
        Toast.makeText(requireContext(), this, Toast.LENGTH_SHORT).show()
    }
}
