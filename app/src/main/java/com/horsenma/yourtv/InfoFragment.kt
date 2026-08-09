package com.horsenma.yourtv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginStart
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import com.horsenma.yourtv.databinding.InfoBinding
import com.horsenma.yourtv.models.TVModel


class InfoFragment : Fragment() {
    private var _binding: InfoBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler()
    private val delay: Long = 5000
    // 位图复用：切台不再每次新建 Bitmap/Canvas，避免内存抖动
    private var logoBitmap: Bitmap? = null
    private var logoCanvas: Canvas? = null
    private val logoPaint = Paint()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = InfoBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as YourTVApplication

        binding.info.layoutParams.width = application.px2Px(binding.info.layoutParams.width)
        binding.info.layoutParams.height = application.px2Px(binding.info.layoutParams.height)

        val layoutParams = binding.info.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.bottomMargin = application.px2Px(binding.info.marginBottom)
        binding.info.layoutParams = layoutParams

        binding.logo.layoutParams.width = application.px2Px(binding.logo.layoutParams.width)
        var padding = application.px2Px(binding.logo.paddingTop)
        binding.logo.setPadding(padding, padding, padding, padding)
        binding.main.layoutParams.width = application.px2Px(binding.main.layoutParams.width)
        padding = application.px2Px(binding.main.paddingTop)
        binding.main.setPadding(padding, padding, padding, padding)

        val layoutParamsMain = binding.main.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsMain.marginStart = application.px2Px(binding.main.marginStart)
        binding.main.layoutParams = layoutParamsMain

        val layoutParamsDesc = binding.desc.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsDesc.topMargin = application.px2Px(binding.desc.marginTop)
        binding.desc.layoutParams = layoutParamsDesc

        binding.title.textSize = application.px2PxFont(binding.title.textSize)
        binding.desc.textSize = application.px2PxFont(binding.desc.textSize)

        binding.container.layoutParams.width = application.shouldWidthPx()
        binding.container.layoutParams.height = application.shouldHeightPx()

        _binding!!.root.visibility = View.GONE
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as MainActivity).ready()
    }

    fun show(tvModel: TVModel) {
        // TODO make sure attached
        if (!isAdded) {
            Log.e(TAG, "Fragment not attached to a context.")
            return
        }

        val tv = tvModel.tv

        val context = requireContext()
        val application = context.applicationContext as YourTVApplication
        val imageHelper = application.imageHelper

        binding.title.text = tv.title

        when (tv.title) {
            else -> {
                val width = 300
                val height = 180
                val bitmap = logoBitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    logoBitmap = it
                }
                val canvas = logoCanvas ?: Canvas(bitmap).also {
                    logoCanvas = it
                }
                // 清空上一频道的数字残留
                canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                val channelNum = if (tv.number == -1) tv.id.plus(1) else tv.number
                var size = 150f
                if (channelNum > 99) {
                    size = 100f
                }
                if (channelNum > 999) {
                    size = 75f
                }
                logoPaint.apply {
                    color = ContextCompat.getColor(context, R.color.title_blur)
                    textSize = size
                    textAlign = Paint.Align.CENTER
                }
                val x = width / 2f
                val y = height / 2f - (logoPaint.descent() + logoPaint.ascent()) / 2
                canvas.drawText(channelNum.toString(), x, y, logoPaint)

                val name = if (tv.name.isNotEmpty()) { tv.name } else { tv.title }
                imageHelper.loadImage(name, binding.logo, bitmap, tv.logo)
            }
        }

        val now = Utils.getDateTimestamp()
        val epg = tvModel.epg.value
        // 只显示"进行中"节目；无进行中节目时回退最近一个已结束节目（带时间标注）
        val displayEpg = epg
            ?.filter { it.beginTime < now && it.endTime > now }
            ?.lastOrNull()
            ?: epg?.filter { it.beginTime < now }?.maxByOrNull { it.endTime }
        if (displayEpg != null) {
            binding.desc.text = context.getString(
                R.string.epg_program_with_time,
                Utils.getDateFormat("HH:mm", displayEpg.beginTime),
                Utils.getDateFormat("HH:mm", displayEpg.endTime),
                displayEpg.title
            )
        } else {
            binding.desc.text = context.getString(R.string.wonderful_program)
        }

        handler.removeCallbacks(removeRunnable)
        view?.visibility = View.VISIBLE
        handler.postDelayed(removeRunnable, delay)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(removeRunnable, delay)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(removeRunnable)
    }

    private val removeRunnable = Runnable {
        view?.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "InfoFragment"
    }
}
