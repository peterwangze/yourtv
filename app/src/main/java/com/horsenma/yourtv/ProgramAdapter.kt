package com.horsenma.yourtv

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.horsenma.yourtv.data.EPG
import com.horsenma.yourtv.databinding.ProgramItemBinding


class ProgramAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private var epgList: List<EPG>,
    private var index: Int,
) :
    RecyclerView.Adapter<ProgramAdapter.ViewHolder>() {

    private var listener: ItemListener? = null
    private var focused: View? = null
    val application = context.applicationContext as YourTVApplication

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(context)
        val binding = ProgramItemBinding.inflate(inflater, parent, false)

        val textSize = application.px2PxFont(binding.title.textSize)
        binding.title.textSize = textSize
        binding.description.textSize = textSize

        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true
        return ViewHolder(context, binding)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val epg = epgList[position]
        val isCurrent = position == index
        val view = viewHolder.itemView

        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            listener?.onItemFocusChange(epg, hasFocus)
            if (hasFocus) {
                viewHolder.focus(true, isCurrent)
                focused = view
            } else {
                viewHolder.focus(false, isCurrent)
            }
        }

        view.setOnKeyListener { _, keyCode, event: KeyEvent? ->
            if (event?.action == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return@setOnKeyListener true
                }
            }
            if (event?.action == KeyEvent.ACTION_DOWN) {
                // If it is already the first item and you continue to move up...
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                    val p = getItemCount() - 1

                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                        p,
                        0
                    )

                    recyclerView.postDelayed({
                        val v = recyclerView.findViewHolderForAdapterPosition(p)
                        v?.itemView?.isSelected = true
                        v?.itemView?.requestFocus()
                    }, 0)
                }


                // If it is the last item and you continue to move down...
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == getItemCount() - 1) {
                    val p = 0

                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                        p,
                        0
                    )

                    recyclerView.postDelayed({
                        val v = recyclerView.findViewHolderForAdapterPosition(p)
                        v?.itemView?.isSelected = true
                        v?.itemView?.requestFocus()
                    }, 0)
                }

                return@setOnKeyListener listener?.onKey(keyCode) == true
            }
            false
        }

        viewHolder.bindTitle(epg, isCurrent)
    }

    override fun getItemCount() = epgList.size

    fun updateData(epgList: List<EPG>, index: Int) {
        this.epgList = epgList
        this.index = index
        notifyDataSetChanged()
    }

    class ViewHolder(private val context: Context, private val binding: ProgramItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bindTitle(epg: EPG, isCurrent: Boolean) {
            binding.title.text = "${Utils.getDateFormat("HH:mm", epg.beginTime)}-${
                Utils.getDateFormat(
                    "HH:mm",
                    epg.endTime
                )
            }"
            binding.description.text = epg.title
            binding.badge.visibility = if (isCurrent) View.VISIBLE else View.GONE
            if (!isCurrent) {
                binding.root.setBackgroundResource(R.color.blur)
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.description_blur))
                binding.description.setTextColor(ContextCompat.getColor(context, R.color.description_blur))
            } else {
                binding.root.setBackgroundResource(R.color.focus_10)
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.white))
                binding.description.setTextColor(ContextCompat.getColor(context, R.color.white))
            }
        }

        fun focus(hasFocus: Boolean, isCurrent: Boolean) {
            if (hasFocus) {
                binding.root.setBackgroundResource(R.drawable.focus_item_background)
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.white))
                binding.description.setTextColor(ContextCompat.getColor(context, R.color.white))
            } else {
                if (isCurrent) {
                    binding.root.setBackgroundResource(R.color.focus_10)
                    binding.title.setTextColor(ContextCompat.getColor(context, R.color.white))
                    binding.description.setTextColor(ContextCompat.getColor(context, R.color.white))
                } else {
                    binding.root.setBackgroundResource(R.color.blur)
                    binding.title.setTextColor(ContextCompat.getColor(context, R.color.description_blur))
                    binding.description.setTextColor(ContextCompat.getColor(context, R.color.description_blur))
                }
            }
        }
    }

    fun scrollToPositionAndSelect(position: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        layoutManager?.let {
            recyclerView.postDelayed({
                // 当前节目居中显示，便于看到"现在"与接下来的节目
                val rowHeight = application.px2Px(51)
                val offset = ((recyclerView.height - rowHeight) / 2).coerceAtLeast(0)
                it.scrollToPositionWithOffset(position, offset)

                val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
                viewHolder?.itemView?.apply {
                    isSelected = true
                    requestFocus()
                }
            }, 0)
        }
    }

    interface ItemListener {
        fun onItemFocusChange(epg: EPG, hasFocus: Boolean)
        fun onKey(keyCode: Int): Boolean
    }

    fun setItemListener(listener: ItemListener) {
        this.listener = listener
    }

    companion object {
        private const val TAG = "ProgramAdapter"
    }
}

