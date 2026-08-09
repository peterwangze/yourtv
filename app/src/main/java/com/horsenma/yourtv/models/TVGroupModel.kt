package com.horsenma.yourtv.models

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import android.util.Log
import com.horsenma.yourtv.SP

/** 菜单导航条目：显示名 + 底层分组索引 + 所属一级分类 */
data class NavEntry(val name: String, val flatIndex: Int, val category: String)

class TVGroupModel : ViewModel() {
    var version = 0
    var isInLikeMode = false

    /** 当前下钻的一级分类（地方/海外/其他），null = 顶级分类视图 */
    private var _navCategory: String? = null
    val navCategory: String?
        get() = _navCategory

    fun isDrilled(): Boolean = _navCategory != null

    private val _tvGroup = MutableLiveData<List<TVListModel>>()
    val tvGroup: LiveData<List<TVListModel>>
        get() = _tvGroup
    val tvGroupValue: List<TVListModel>
        get() = _tvGroup.value ?: emptyList()

    private val _position = MutableLiveData<Int>()
    val position: LiveData<Int>
        get() = _position
    val positionValue: Int
        get() = _position.value ?: 0

    private val _current = MutableLiveData<TVModel?>()
    val current: LiveData<TVModel?>
        get() = _current

    fun setPosition(position: Int) {
        _position.setValueSafe(position)
    }

    fun setCurrent(tvModel: TVModel) {
        _current.setValueSafe(tvModel)
        // 定位频道所属的真实分组（央视/卫视/地方/海外/其他），
        // 跳过"我的收藏/全部频道"（索引 0/1，全部频道包含所有频道会抢先命中），
        // 避免播放位置停留在"全部频道"导致菜单恢复导航时定位错误
        var groupIdx = -1
        for (i in 2 until tvGroupValue.size) {
            if (tvGroupValue[i].tvList.value?.any { it.tv.id == tvModel.tv.id } == true) {
                groupIdx = i
                break
            }
        }
        if (groupIdx >= 0) {
            setPosition(groupIdx)
            setPositionPlaying(groupIdx)
        } else {
            // 旧对象（聚合前）按 分类+规范名 锚定到新列表中的同频道；
            // 绝不把过期对象追加进分组（会造成重复频道与侧边栏漂移）
            val key = com.horsenma.yourtv.models.ChannelClassifier.mergeKey(
                tvModel.tv.title, tvModel.tv.group
            )
            var anchoredIdx = -1
            for (i in 2 until tvGroupValue.size) {
                val list = tvGroupValue[i].tvList.value ?: continue
                val idx = list.indexOfFirst {
                    com.horsenma.yourtv.models.ChannelClassifier.mergeKey(it.tv.title, it.tv.group) == key
                }
                if (idx >= 0) {
                    anchoredIdx = i
                    tvGroupValue[i].setPosition(idx)
                    break
                }
            }
            if (anchoredIdx >= 0) {
                setPosition(anchoredIdx)
                setPositionPlaying(anchoredIdx)
            } else {
                setPositionPlaying(positionValue)
            }
        }
        setChange()
    }

    private val _positionPlaying = MutableLiveData<Int>()
    val positionPlaying: LiveData<Int>
        get() = _positionPlaying
    val positionPlayingValue: Int
        get() = _positionPlaying.value ?: DEFAULT_POSITION_PLAYING

    fun setPositionPlaying(position: Int) {
        _positionPlaying.setValueSafe(position)
        SP.positionGroup = position
    }

    fun setPositionPlaying() {
        setPositionPlaying(positionValue)
    }

    private val _change = MutableLiveData<Int>()
    val change: LiveData<Int>
        get() = _change

    fun setChange() {
        _change.setValueSafe(version)
        version++
    }

    fun setTVListModelList(tvGroup: List<TVListModel>) {
        _tvGroup.setValueSafe(tvGroup)
    }

    fun addTVListModel(listTVModel: TVListModel) {
        _tvGroup.setValueSafe(tvGroupValue.toMutableList().apply {
            add(listTVModel)
        })
    }

    fun getTVListModel(): TVListModel? {
        return getTVListModel(positionValue)
    }

    /** 按底层分组索引取分组（不过滤收藏组），供导航/菜单使用 */
    fun getGroupAt(flatIndex: Int): TVListModel? {
        if (flatIndex < 0 || flatIndex >= tvGroupValue.size) {
            return null
        }
        return tvGroupValue[flatIndex]
    }

    /** 顶级导航：收藏 / 全部 / 央视 / 卫视 / 地方 / 海外 / 其他（只列有内容的分类） */
    fun topEntries(): List<NavEntry> {
        val entries = mutableListOf<NavEntry>()
        getGroupAt(0)?.let { entries.add(NavEntry(it.getName(), 0, "")) }
        getGroupAt(1)?.let { entries.add(NavEntry(it.getName(), 1, "")) }
        // 收藏/全部 为特殊分组（索引 0/1），不参与一级分类扫描
        val seen = tvGroupValue.drop(2).mapIndexedNotNull { offset, group ->
            val index = offset + 2
            val cat = com.horsenma.yourtv.models.ChannelClassifier.topCategoryOfGroup(group.getName())
            if (cat.isNotEmpty()) cat to index else null
        }
        for (cat in com.horsenma.yourtv.models.ChannelClassifier.TOP_CATEGORIES) {
            val first = seen.firstOrNull { it.first == cat }?.second ?: continue
            entries.add(NavEntry(cat, first, cat))
        }
        return entries
    }

    /** 二级导航：某分类下的地区/分类分组（地方=省份，海外=国家，其他=分类名） */
    fun subEntries(category: String): List<NavEntry> {
        return tvGroupValue.drop(2).mapIndexedNotNull { offset, group ->
            val index = offset + 2
            val cat = com.horsenma.yourtv.models.ChannelClassifier.topCategoryOfGroup(group.getName())
            if (cat == category) NavEntry(group.getName(), index, category) else null
        }
    }

    /** 当前显示的导航条目（顶级 or 下钻） */
    fun navEntries(): List<NavEntry> {
        val cat = _navCategory
        return if (cat != null) subEntries(cat) else topEntries()
    }

    fun navEntryAt(position: Int): NavEntry? = navEntries().getOrNull(position)

    fun navFlatIndexAt(position: Int): Int = navEntryAt(position)?.flatIndex ?: 0

    /** 下钻到分类（地方/海外/其他），并把当前分组切到该分类下第一个地区 */
    fun enterCategory(category: String) {
        _navCategory = category
        val subs = subEntries(category)
        if (subs.isNotEmpty()) {
            val cur = getGroupAt(positionValue)?.getName()
                ?.let { com.horsenma.yourtv.models.ChannelClassifier.topCategoryOfGroup(it) }
            if (cur != category) {
                setPosition(subs.first().flatIndex)
            }
        }
    }

    /** 返回顶级分类视图 */
    fun exitCategory() {
        _navCategory = null
    }

    /**
     * 根据底层分组索引恢复导航状态：地方/海外/其他 恢复为下钻视图，
     * 央视/卫视/收藏/全部 恢复为顶级视图。
     */
    fun restoreNav(flatIndex: Int) {
        val group = getGroupAt(flatIndex) ?: return
        // 收藏/全部为顶级视图
        if (flatIndex <= 1) {
            _navCategory = null
            Log.d(TAG, "restoreNav: special group flat=$flatIndex, nav=top")
            return
        }
        val cat = com.horsenma.yourtv.models.ChannelClassifier.topCategoryOfGroup(group.getName())
        _navCategory = if (com.horsenma.yourtv.models.ChannelClassifier.isThreeLevelCategory(cat)) cat else null
        Log.d(TAG, "restoreNav: flat=$flatIndex group=${group.getName()} cat=$cat nav=${_navCategory}")
    }

    fun getTVListModel(idx: Int): TVListModel? {
        if (idx < 0 || idx >= size()) {
            return null
        }

        if (SP.showAllChannels) {
            return tvGroupValue[idx]
        }

        return tvGroupValue.filterIndexed { index, _ -> index != 1 }[idx]
    }

    private fun getTVListModelNotFilter(idx: Int): TVListModel? {
        if (idx < 0 || idx >= tvGroupValue.size) {
            return null
        }

        return tvGroupValue[idx]
    }

    // get & set
    fun getPosition(position: Int): TVModel? {

        // No item
        if (tvGroupValue[1].size() == 0) {
            return null
        }

        var count = 0
        for ((index, i) in tvGroupValue.withIndex()) {
            val countBefore = count
            count += i.size()
            if (count > position) {
                setPosition(index)
                val listPosition = position - countBefore
                i.setPosition(listPosition)
                return i.getTVModel(listPosition)
            }
        }

        return null
    }

    fun getCurrent(): TVModel? {

        // No item
        if (tvGroupValue.size < 3 || tvGroupValue[1].size() == 0) {
            return null
        }

        return getCurrentList()?.getCurrent()
    }

    /**
     * 只读获取当前频道标题。与 getCurrent() 不同，不会触碰任何 LiveData setter，
     * 可在后台线程（如解析链路 IO 线程）安全调用。
     */
    fun getCurrentTitle(): String? {
        // No item
        if (tvGroupValue.size < 3 || tvGroupValue[1].size() == 0) {
            return null
        }

        val currentList = getCurrentList() ?: return null
        val list = currentList.tvList.value ?: return null
        if (list.isEmpty()) {
            return null
        }

        val idx = currentList.positionValue
        return list.getOrNull(idx)?.tv?.title ?: list.first().tv.title
    }

    fun getCurrentList(): TVListModel? {
        return getTVListModelNotFilter(positionValue)
    }

    fun getFavoritesList(): TVListModel? {
        return getTVListModelNotFilter(0)
    }

    fun getAllList(): TVListModel? {
        return getTVListModelNotFilter(1)
    }

    // get & set
    // keep: In the current list loop
    fun getPrev(keep: Boolean = false): TVModel? {
        // No item
        if (tvGroupValue.size < 3 || tvGroupValue[1].size() == 0) {
            return null
        }

        var tvListModel = getCurrentList() ?: return null

        if (keep) {
            return tvListModel.getPrev()
        }

        // Prev tvListModel
        if (tvListModel.positionPlayingValue == 0) {
            var p = (tvGroupValue.size + positionPlayingValue - 1) % tvGroupValue.size
            if (p == 1 || p == 0) {
                // 最後一組
                p = (tvGroupValue.size - 1) % tvGroupValue.size
            }
            setPositionPlaying(p)
            setPosition(p)

//            Log.i(TAG, "group positionPlaying $p/${tvGroupValue.size - 1}")
            tvListModel = getTVListModelNotFilter(p)!!
            return tvListModel.getTVModel(tvListModel.size() - 1)
        }

        return tvListModel.getPrev()
    }

    // get & set
    fun getNext(keep: Boolean = false): TVModel? {
        // No item
        if (tvGroupValue.size < 3 || tvGroupValue[1].size() == 0) {
            return null
        }

        var tvListModel = getCurrentList() ?: return null

        if (keep) {
            return tvListModel.getNext()
        }

        // Next tvListModel
        if (tvListModel.positionPlayingValue == tvListModel.size() - 1) {
            var p = (positionPlayingValue + 1) % tvGroupValue.size
            if (p == 0) {
                // 第一組
                p = 2
            }
            setPositionPlaying(p)
            setPosition(p)

//            Log.i(TAG, "group positionPlaying $p/${tvGroupValue.size - 1}")
            tvListModel = getTVListModelNotFilter(p)!!
            return tvListModel.getTVModel(0)
        }

        return tvListModel.getNext()
    }

    fun defaultPosition(): Int {
        return if (tvGroupValue.size > 2) 2 else 1
    }

    fun initPosition() {
        setPosition(defaultPosition())
        setPositionPlaying()
    }

    init {
        setPosition(SP.positionGroup)
        setPositionPlaying()
        isInLikeMode = SP.defaultLike && positionValue == 0
    }

    fun size(): Int {
        if (SP.showAllChannels) {
            return tvGroupValue.size
        }

        return tvGroupValue.size - 1
    }

    companion object {
        const val TAG = "TVGroupModel"
        const val DEFAULT_POSITION_PLAYING = -1
    }
}
