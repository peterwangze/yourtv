package com.horsenma.yourtv.models

/**
 * 频道智能分类器：按电视家等主流电视 App 的习惯，把来自任意源的频道归类为
 * 央视 / 卫视 / 地方频道(按省份,含港澳台) / 海外频道(按国家/地区) / 其他。
 * 同时提供规范名用于跨源合并（同一频道在不同源里写法不同也能聚合多线路）。
 */
object ChannelClassifier {

    const val CAT_CCTV = "央视"
    const val CAT_WEISHI = "卫视"
    const val CAT_LOCAL = "地方"
    const val CAT_OVERSEAS = "海外"
    const val CAT_OTHER = "其他"

    /** 一级分类固定顺序：央视 → 卫视 → 地方 → 海外 → 其他 */
    val TOP_CATEGORIES: List<String> = listOf(CAT_CCTV, CAT_WEISHI, CAT_LOCAL, CAT_OVERSEAS, CAT_OTHER)

    data class Classification(val category: String, val region: String)

    /** 显示分组名：央视 / 卫视 / 省份 / 国家 / 其他-原分组 */
    fun displayGroup(title: String, groupHint: String?): String {
        val c = classify(title, groupHint)
        return when (c.category) {
            CAT_CCTV -> CAT_CCTV
            CAT_WEISHI -> CAT_WEISHI
            CAT_LOCAL, CAT_OVERSEAS -> c.region.ifEmpty { CAT_OTHER }
            else -> localizeOther(groupHint).ifEmpty { CAT_OTHER }
        }
    }

    /** 跨源合并键：分类|地区|规范名（其他类再叠加原分组，避免不同地区"都市频道"误合并） */
    fun mergeKey(title: String, groupHint: String?): String {
        val effective = canonicalAlias(title) ?: title
        val c = classify(effective, groupHint)
        return when (c.category) {
            CAT_CCTV -> "央视||" + cctvCanonicalId(title)
            CAT_WEISHI -> "卫视||" + normalizeName(effective)
            CAT_LOCAL, CAT_OVERSEAS -> c.category + "|" + c.region + "|" + normalizeName(effective)
            else -> CAT_OTHER + "|" + localizeOther(groupHint) + "|" + normalizeName(effective)
        }
    }

    /**
    * CCTV 系列跨源合并键：CCTV1/CCTV-1 综合/央视一套/中央一套 等写法统一为 cctv1，
    * 避免同一央视频道因写法差异被拆成多条。
    */
    fun cctvMergeKey(title: String): String {
        val c = classify(title, null)
        return if (c.category == CAT_CCTV) "央视||" + cctvCanonicalId(title) else mergeKey(title, null)
    }

    /** Stable numeric order for CCTV entries in the channel list. */
    fun channelSortOrder(title: String, groupHint: String? = null): Int {
        if (classify(title, groupHint).category != CAT_CCTV) return Int.MAX_VALUE
        val lower = title.lowercase()
        if (Regex("cctv\\s*[-－+]?\\s*8k").containsMatchIn(lower)) return 8000
        if (Regex("cctv\\s*[-－+]?\\s*4k").containsMatchIn(lower)) return 4000
        return Regex("cctv\\s*[-－+]?\\s*(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            ?: Int.MAX_VALUE - 1
    }

    /** 英文/拼音频道名 → 中文规范名（跨源合并与分类用，如 Hunan TV → 湖南卫视） */
    fun canonicalAlias(name: String): String? {
        return ALIASES[normalizeName(name)]
    }

    /** Canonical user-facing title used after cross-source aggregation. */
    fun displayName(name: String): String {
        canonicalAlias(name)?.let { return it }
        val id = cctvCanonicalId(name)
        when {
            id == "cgtn" -> return "CGTN"
            id.startsWith("cctv") -> {
                val suffix = id.removePrefix("cctv")
                return "CCTV" + if (suffix.endsWith("plus")) {
                    suffix.removeSuffix("plus") + "+"
                } else {
                    suffix.uppercase()
                }
            }
            id.startsWith("cetv") -> return "CETV" + id.removePrefix("cetv")
        }
        // 非 CCTV 频道：清理展示后缀（清晰度/地区/播放限制标记），
        // 避免用户看到 "北京衛視 (1080p) [Geo-blocked]" 式原始标题
        var clean = name.trim()
        clean = clean.replace(Regex("[（(][^（）()]*[）)]"), "")
        clean = clean.replace(Regex("\\[[^\\]]*\\]"), "")
        clean = clean.replace(
            Regex("(?i)(4k|8k|2160p|1440p|1080p|720p|480p|360p|fhd|hd|sd|uhd|超清|高清|标清|蓝光|流畅|极速|hdr|geo-blocked|geoblocked|not 24/7|24/7)\\s*$"),
            ""
        )
        clean = clean.replace(Regex("[\\s\\-—–_.·,，、:：;；!！?？/\\\\|\\[\\]【】\"'‘’“”]+$"), "")
        return clean.ifBlank { name.trim() }
    }

    /** 常见繁体电视用字 → 简体（提升跨源合并与分类一致性） */
    private fun simplifyTraditional(s: String): String {
        if (s.none { it in TRADITIONAL_TO_SIMPLIFIED }) return s
        return s.map { TRADITIONAL_TO_SIMPLIFIED[it] ?: it }.joinToString("")
    }

    private val TRADITIONAL_TO_SIMPLIFIED: Map<Char, Char> = mapOf(
        '衛' to '卫', '視' to '视', '頻' to '频', '聞' to '闻', '綜' to '综',
        '體' to '体', '錄' to '录', '紀' to '纪', '實' to '实', '兒' to '儿',
        '樂' to '乐', '國' to '国', '際' to '际', '財' to '财', '經' to '经',
        '娛' to '娱', '劇' to '剧', '戲' to '戏', '時' to '时', '訊' to '讯',
        '臺' to '台', '灣' to '湾', '東' to '东', '廣' to '广', '鳳' to '凤',
        '無' to '无', '線' to '线', '電' to '电', '畫' to '画', '網' to '网',
        '華' to '华', '語' to '语', '說' to '说', '會' to '会', '館' to '馆',
        '業' to '业', '動' to '动', '數' to '数', '碼' to '码', '眾' to '众',
        '觀' to '观', '賞' to '赏', '藝' to '艺', '術' to '术', '節' to '节',
        '氣' to '气', '報' to '报', '導' to '导', '題' to '题', '書' to '书',
        '單' to '单', '雙' to '双', '學' to '学', '習' to '习', '醫' to '医',
        '療' to '疗', '優' to '优', '質' to '质', '傳' to '传', '統' to '统'
    )

    private fun cctvCanonicalId(name: String): String {
        val lower = name.lowercase()
        if (lower.contains("cgtn")) return "cgtn"
        if (lower.contains("cetv") || name.contains("中国教育")) {
            val m = Regex("cetv\\s*[-－]?\\s*(\\d+)").find(lower)
            return if (m != null) "cetv" + m.groupValues[1] else "cetv"
        }
        Regex("cctv\\s*[-－]?\\s*(4k|8k)").find(lower)?.let { return "cctv" + it.groupValues[1] }
        // Accept the common `CCTV+ 1` spelling used by several IPTV feeds.
        Regex("cctv\\s*\\+\\s*(\\d+)").find(lower)?.let { return "cctv" + it.groupValues[1] }
        Regex("cctv\\s*[-－]?\\s*(\\d+)\\s*\\+").find(lower)?.let { return "cctv" + it.groupValues[1] + "plus" }
        Regex("cctv\\s*[-－]?\\s*(\\d+)").find(lower)?.let { return "cctv" + it.groupValues[1] }
        Regex("(中央|央视)([一二三四五六七八九十]+)\\s*套").find(name)?.let {
            val num = chineseNumeralToInt(it.groupValues[2])
            if (num != null) return "cctv$num"
        }
        return normalizeName(name)
    }

    private fun chineseNumeralToInt(s: String): Int? {
        val digits = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '两' to 2)
        return when {
            s == "十" -> 10
            s.length == 1 -> digits[s[0]]
            s.length == 2 && s[0] == '十' -> 10 + (digits[s[1]] ?: return null)
            s.length == 2 && s[1] == '十' -> (digits[s[0]] ?: return null) * 10
            else -> null
        }
    }

    /** 分组排序：央视 → 卫视 → 地方(按省份顺序) → 海外(按国家顺序) → 其他 */
    fun sortGroups(groups: Set<String>): List<String> {
        return groups.sortedWith(
            compareBy({ groupRank(it) }, { regionRank(it) }, { it })
        )
    }

    /**
     * 分组名 → 一级分类（央视/卫视/地方/海外/其他）。
     * 分组名即 displayGroup 的输出：央视/卫视 本身，地方=省份名，海外=国家名，其他=分类名。
     */
    fun topCategoryOfGroup(group: String): String = when {
        group == CAT_CCTV -> CAT_CCTV
        group == CAT_WEISHI -> CAT_WEISHI
        PROVINCES.contains(group) -> CAT_LOCAL
        COUNTRIES.contains(group) -> CAT_OVERSEAS
        // 地方频道兜底分组（无省市信息的地方台）
        group == "未分类" -> CAT_LOCAL
        else -> CAT_OTHER
    }

    /** 该分类是否需要三级（地方/海外/其他为 一级分类→地区/分类→频道，央视/卫视为 一级→频道） */
    fun isThreeLevelCategory(category: String): Boolean =
        category == CAT_LOCAL || category == CAT_OVERSEAS || category == CAT_OTHER

    /** 供频道列表排序用的分组优先级：央视=0 … 其他=4 */
    fun rankOfGroup(groupName: String): Int = groupRank(groupName)

    /** 名称规范化：去括号内容/清晰度后缀/空白标点，用于跨源合并 */
    fun normalizeName(name: String): String {
        var s = name.lowercase()
        s = simplifyTraditional(s)
        s = s.replace(Regex("[（(][^（）()]*[）)]"), "")
        s = s.replace(Regex("\\[[^\\]]*\\]"), "")
        s = s.replace(
            Regex("(4k|8k|2160p|1440p|1080p|720p|480p|360p|fhd|hd|sd|uhd|超清|高清|标清|蓝光|流畅|极速|hdr)$"),
            ""
        )
        s = s.replace(Regex("[\\s\\-—–_.·,，、:：;；!！?？/\\\\|\\[\\]【】\"'‘’“”]+"), "")
        return s
    }

    fun classify(title: String, groupHint: String?): Classification {
        val name = canonicalAlias(title) ?: title.trim()
        val hint = groupHint?.trim().orEmpty()
        val lower = name.lowercase()
        val hintLower = hint.lowercase()

        // 1. 央视：CCTV / CGTN / CETV / 央视 / 中央 / 中国教育
        if (lower.contains("cctv") || lower.contains("cgtn") || lower.contains("cetv") ||
            name.contains("央视") || name.contains("中央") || name.contains("中国教育")
        ) {
            return Classification(CAT_CCTV, "")
        }
        // 分组提示兜底：标题无关键词但源分组是央视频道/央视IPV4 等
        if (hint.contains("央视") || hint.contains("中央") || hintLower.contains("cctv")) {
            return Classification(CAT_CCTV, "")
        }

        // 2. 港澳台（先于卫视规则：凤凰卫视/香港卫视/澳门卫视等归入港澳台）
        matchHkTwMo(name, hint)?.let { return Classification(CAT_LOCAL, it) }

        // 3. 地方卫视（含繁体"衛視"写法）
        if (name.contains("卫视") || name.contains("衛視") || hint.contains("卫视")) {
            return Classification(CAT_WEISHI, "")
        }

        // 4. 地方频道：省份/城市
        matchProvince(name, hint)?.let { return Classification(CAT_LOCAL, it) }

        // 5. 海外频道：国家/地区关键词或海外品牌
        matchCountry(name, hint)?.let { return Classification(CAT_OVERSEAS, it) }

        // 6. 分组提示明确为地方频道/地方（TXT 类源的 #genre# 分组），
        //    标题无法识别省市时归入 地方›未分类，保证地方频道不丢
        if (hint.contains("地方") || hintLower.contains("local")) {
            return Classification(CAT_LOCAL, "未分类")
        }

        return Classification(CAT_OTHER, "")
    }

    private fun matchHkTwMo(name: String, hint: String): String? {
        val twBrands = listOf(
            "台视", "中视", "华视", "民视", "三立", "东森", "中天", "tvbs", "八大", "年代",
            "纬来", "非凡", "壹电视", "寰宇", "台湾", "台北", "高雄", "台中", "台南", "新北"
        )
        if (twBrands.any { name.contains(it, ignoreCase = true) }) return "台湾"
        val hkBrands = listOf(
            "凤凰", "香港", "无线", "明珠台", "翡翠台", "viu", "开电视", "有线新闻",
            "亚洲电视", "atv", "now新闻", "now财经", "港台"
        )
        if (hkBrands.any { name.contains(it, ignoreCase = true) }) return "香港"
        val moBrands = listOf("澳门", "澳亚", "澳视")
        if (moBrands.any { name.contains(it, ignoreCase = true) }) return "澳门"
        // 英文写法兜底：Hong Kong / Taiwan / Macau
        if (name.contains("hong kong", ignoreCase = true) || name.contains("rthk", ignoreCase = true)) return "香港"
        if (name.contains("taiwan", ignoreCase = true) || name.contains("taipei", ignoreCase = true) ||
            name.contains("kaohsiung", ignoreCase = true)) return "台湾"
        if (name.contains("macau", ignoreCase = true) || name.contains("macao", ignoreCase = true)) return "澳门"
        if (hint.contains("香港")) return "香港"
        if (hint.contains("澳门")) return "澳门"
        if (hint.contains("台湾")) return "台湾"
        if (hint.contains("hong kong", ignoreCase = true)) return "香港"
        if (hint.contains("taiwan", ignoreCase = true)) return "台湾"
        if (hint.contains("macau", ignoreCase = true) || hint.contains("macao", ignoreCase = true)) return "澳门"
        return null
    }

    private fun matchProvince(name: String, hint: String): String? {
        for ((city, province) in CITY_TO_PROVINCE) {
            if (name.contains(city)) return province
        }
        var best: String? = null
        for (p in PROVINCES) {
            if (name.contains(p) && (best == null || p.length > best.length)) best = p
        }
        // 常用简称/英文兜底：内蒙、内蒙古、广西 vs 广东 等
        if (name.contains("内蒙")) return "内蒙古"
        if (name.contains("内蒙古") || name.contains("inner mongolia", ignoreCase = true)) return "内蒙古"
        if (name.contains("西藏") || name.contains("藏语") || name.contains("藏文")) return "西藏"
        if (name.contains("新疆")) return "新疆"
        if (best != null) return best
        for (p in PROVINCES) {
            if (hint.contains(p)) return p
        }
        if (hint.contains("内蒙")) return "内蒙古"
        if (hint.contains("西藏") || hint.contains("藏语")) return "西藏"
        if (hint.contains("新疆")) return "新疆"
        return null
    }

    private fun matchCountry(name: String, hint: String): String? {
        for ((country, keys) in COUNTRY_KEYWORDS) {
            if (keys.any { name.contains(it, ignoreCase = true) }) return country
        }
        for ((country, keys) in COUNTRY_KEYWORDS) {
            if (keys.any { hint.contains(it, ignoreCase = true) }) return country
        }
        return null
    }

    private fun groupRank(g: String): Int = when {
        g == CAT_CCTV -> 0
        g == CAT_WEISHI -> 1
        PROVINCES.contains(g) -> 2
        COUNTRIES.contains(g) -> 3
        else -> 4
    }

    private fun regionRank(g: String): Int {
        val idx = PROVINCES.indexOf(g)
        if (idx >= 0) return idx
        val cidx = COUNTRIES.indexOf(g)
        if (cidx >= 0) return cidx
        return Int.MAX_VALUE
    }

    /** 其他类：英文/繁体分组名本地化为中文 */
    fun localizeOther(group: String?): String {
        val g = group?.trim().orEmpty()
        if (g.isEmpty()) return ""
        // 咪咕体育的每日轮换分组："体育-今天05-10" / "体育-明天05-11" → "体育"
        val dailySports = Regex("^(体育)-[今明后昨]天\\d{1,2}-\\d{1,2}$").find(g)
        if (dailySports != null) return dailySports.groupValues[1]
        // 组合分组（iptv-org 的 "Animation;Kids" / "Movies;Series"）取第一个分类
        if (";" in g) {
            val first = g.split(";").first().trim()
            if (first.isNotEmpty()) {
                if (first.any { it.code in 0x4E00..0x9FFF }) return first
                GROUP_NAME_ZH[first.lowercase()]?.let { return it }
            }
        }
        if (g.any { it.code in 0x4E00..0x9FFF }) return g
        return GROUP_NAME_ZH[g.trim().lowercase()] ?: g
    }

    private val GROUP_NAME_ZH: Map<String, String> = mapOf(
        "24h" to "24小时", "animation" to "动画", "auto" to "汽车",
        "business" to "商业", "classic" to "经典", "comedy" to "喜剧",
        "culture" to "文化", "documentary" to "纪录片", "education" to "教育",
        "entertainment" to "娱乐", "family" to "家庭", "general" to "综合",
        "international" to "国际", "kids" to "少儿", "lifestyle" to "生活",
        "local" to "地方", "movies" to "电影", "music" to "音乐",
        "news" to "新闻", "politics" to "政治", "public" to "公共",
        "religious" to "宗教", "science" to "科学", "series" to "剧集", "outdoor" to "户外",
        "shop" to "购物", "sports" to "体育", "travel" to "旅游",
        "undefined" to "其他", "weather" to "天气", "test" to "测试",
        "other" to "其他", "央视" to "央视", "卫视" to "卫视"
    )

    /** 省份（含直辖市/自治区/特别行政区），顺序即分组排序 */
    private val PROVINCES = listOf(
        "北京", "天津", "上海", "重庆", "河北", "山西", "辽宁", "吉林", "黑龙江",
        "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南",
        "广东", "海南", "四川", "贵州", "云南", "陕西", "甘肃", "青海",
        "内蒙古", "广西", "西藏", "宁夏", "新疆", "香港", "澳门", "台湾"
    )

    /** 主要城市 → 省份 */
    private val CITY_TO_PROVINCE = mapOf(
        "广州" to "广东", "深圳" to "广东", "东莞" to "广东", "佛山" to "广东", "珠海" to "广东",
        "汕头" to "广东", "惠州" to "广东", "中山" to "广东", "江门" to "广东", "湛江" to "广东",
        "肇庆" to "广东", "潮州" to "广东", "揭阳" to "广东", "阳江" to "广东", "梅州" to "广东",
        "清远" to "广东", "韶关" to "广东", "茂名" to "广东", "汕尾" to "广东", "河源" to "广东",
        "杭州" to "浙江", "宁波" to "浙江", "温州" to "浙江", "嘉兴" to "浙江", "湖州" to "浙江",
        "绍兴" to "浙江", "金华" to "浙江", "衢州" to "浙江", "台州" to "浙江", "丽水" to "浙江",
        "余姚" to "浙江", "上虞" to "浙江", "云和" to "浙江", "慈溪" to "浙江", "桐乡" to "浙江",
        "诸暨" to "浙江", "嵊州" to "浙江", "兰溪" to "浙江", "义乌" to "浙江", "东阳" to "浙江",
        "南京" to "江苏", "苏州" to "江苏", "无锡" to "江苏", "常州" to "江苏", "南通" to "江苏",
        "扬州" to "江苏", "镇江" to "江苏", "泰州" to "江苏", "盐城" to "江苏", "淮安" to "江苏",
        "连云港" to "江苏", "徐州" to "江苏", "宿迁" to "江苏", "常熟" to "江苏", "昆山" to "江苏",
        "溧水" to "江苏", "溧阳" to "江苏", "宜兴" to "江苏", "江阴" to "江苏", "张家港" to "江苏",
        "武汉" to "湖北", "宜昌" to "湖北", "襄阳" to "湖北", "荆州" to "湖北", "黄冈" to "湖北",
        "十堰" to "湖北", "孝感" to "湖北", "黄石" to "湖北", "鄂州" to "湖北", "咸宁" to "湖北",
        "荆门" to "湖北", "随州" to "湖北", "恩施" to "湖北", "仙桃" to "湖北", "潜江" to "湖北",
        "成都" to "四川", "绵阳" to "四川", "德阳" to "四川", "南充" to "四川", "宜宾" to "四川",
        "泸州" to "四川", "自贡" to "四川", "内江" to "四川", "乐山" to "四川", "遂宁" to "四川",
        "乐至" to "四川", "井研" to "四川", "仁寿" to "四川", "资阳" to "四川",
        "广安" to "四川", "达州" to "四川", "巴中" to "四川", "雅安" to "四川", "眉山" to "四川",
        "西安" to "陕西", "咸阳" to "陕西", "宝鸡" to "陕西", "渭南" to "陕西", "汉中" to "陕西",
        "安康" to "陕西", "延安" to "陕西", "榆林" to "陕西", "商洛" to "陕西", "铜川" to "陕西",
        "济南" to "山东", "青岛" to "山东", "烟台" to "山东", "潍坊" to "山东", "临沂" to "山东",
        "淄博" to "山东", "济宁" to "山东", "泰安" to "山东", "聊城" to "山东", "德州" to "山东",
        "滨州" to "山东", "东营" to "山东", "威海" to "山东", "日照" to "山东", "菏泽" to "山东",
        "沈阳" to "辽宁", "大连" to "辽宁", "鞍山" to "辽宁", "抚顺" to "辽宁", "本溪" to "辽宁",
        "锦州" to "辽宁", "丹东" to "辽宁", "营口" to "辽宁", "盘锦" to "辽宁", "葫芦岛" to "辽宁",
        "长春" to "吉林", "吉林" to "吉林", "四平" to "吉林", "延边" to "吉林", "通化" to "吉林",
        "东丰" to "吉林", "九台" to "吉林", "公主岭" to "吉林", "梅河口" to "吉林", "白山" to "吉林",
        "哈尔滨" to "黑龙江", "齐齐哈尔" to "黑龙江", "大庆" to "黑龙江", "牡丹江" to "黑龙江",
        "七台河" to "黑龙江", "伊春" to "黑龙江", "鹤岗" to "黑龙江", "黑河" to "黑龙江",
        "佳木斯" to "黑龙江", "绥化" to "黑龙江", "鸡西" to "黑龙江", "双鸭山" to "黑龙江",
        "石家庄" to "河北", "唐山" to "河北", "保定" to "河北", "邯郸" to "河北", "秦皇岛" to "河北",
        "廊坊" to "河北", "沧州" to "河北", "邢台" to "河北", "张家口" to "河北", "承德" to "河北",
        "衡水" to "河北", "张家口" to "河北", "任丘" to "河北", "涿州" to "河北", "定州" to "河北",
        "太原" to "山西", "大同" to "山西", "临汾" to "山西", "运城" to "山西", "晋中" to "山西",
        "长治" to "山西", "晋城" to "山西", "阳泉" to "山西", "朔州" to "山西", "忻州" to "山西",
        "吕梁" to "山西", "高平" to "山西", "孝义" to "山西", "介休" to "山西", "汾阳" to "山西",
        "万荣" to "山西", "平遥" to "山西", "侯马" to "山西", "永济" to "山西", "河津" to "山西",
        "郑州" to "河南", "洛阳" to "河南", "开封" to "河南", "新乡" to "河南", "安阳" to "河南",
        "南阳" to "河南", "许昌" to "河南", "平顶山" to "河南", "信阳" to "河南", "焦作" to "河南",
        "濮阳" to "河南", "周口" to "河南", "漯河" to "河南", "驻马店" to "河南", "商丘" to "河南",
        "三门峡" to "河南", "鹤壁" to "河南", "济源" to "河南", "永城" to "河南", "项城" to "河南",
        "长沙" to "湖南", "株洲" to "湖南", "湘潭" to "湖南", "衡阳" to "湖南", "岳阳" to "湖南",
        "常德" to "湖南", "郴州" to "湖南", "益阳" to "湖南", "邵阳" to "湖南", "娄底" to "湖南",
        "永州" to "湖南", "怀化" to "湖南", "张家界" to "湖南", "湘西" to "湖南", "醴陵" to "湖南",
        "南昌" to "江西", "九江" to "江西", "赣州" to "江西", "上饶" to "江西", "宜春" to "江西",
        "吉安" to "江西", "抚州" to "江西", "萍乡" to "江西", "新余" to "江西", "景德镇" to "江西",
        "鹰潭" to "江西", "樟树" to "江西", "丰城" to "江西", "高安" to "江西", "瑞金" to "江西",
        "合肥" to "安徽", "芜湖" to "安徽", "蚌埠" to "安徽", "淮南" to "安徽", "马鞍山" to "安徽",
        "淮北" to "安徽", "铜陵" to "安徽", "安庆" to "安徽", "黄山" to "安徽", "滁州" to "安徽",
        "阜阳" to "安徽", "宿州" to "安徽", "六安" to "安徽", "亳州" to "安徽", "池州" to "安徽",
        "宣城" to "安徽", "巢湖" to "安徽", "桐城" to "安徽", "天长" to "安徽", "明光" to "安徽",
        "昆明" to "云南", "曲靖" to "云南", "玉溪" to "云南", "大理" to "云南", "丽江" to "云南",
        "保山" to "云南", "昭通" to "云南", "普洱" to "云南", "临沧" to "云南", "红河" to "云南",
        "文山" to "云南", "楚雄" to "云南", "西双版纳" to "云南", "德宏" to "云南", "迪庆" to "云南",
        "贵阳" to "贵州", "遵义" to "贵州", "六盘水" to "贵州", "安顺" to "贵州", "毕节" to "贵州",
        "铜仁" to "贵州", "黔东南" to "贵州", "黔南" to "贵州", "黔西南" to "贵州", "凯里" to "贵州",
        "南宁" to "广西", "柳州" to "广西", "桂林" to "广西", "梧州" to "广西", "北海" to "广西",
        "防城港" to "广西", "钦州" to "广西", "贵港" to "广西", "玉林" to "广西", "百色" to "广西",
        "贺州" to "广西", "河池" to "广西", "来宾" to "广西", "崇左" to "广西", "桂平" to "广西",
        "海口" to "海南", "三亚" to "海南", "儋州" to "海南", "琼海" to "海南", "万宁" to "海南",
        "福州" to "福建", "厦门" to "福建", "泉州" to "福建", "漳州" to "福建", "莆田" to "福建",
        "云霄" to "福建", "龙岩" to "福建", "三明" to "福建", "南平" to "福建", "宁德" to "福建",
        "兰州" to "甘肃", "天水" to "甘肃", "嘉峪关" to "甘肃", "金昌" to "甘肃", "白银" to "甘肃",
        "武威" to "甘肃", "张掖" to "甘肃", "平凉" to "甘肃", "酒泉" to "甘肃", "庆阳" to "甘肃",
        "定西" to "甘肃", "陇南" to "甘肃", "临夏" to "甘肃", "甘南" to "甘肃", "敦煌" to "甘肃",
        "卡酷少儿" to "北京", // 北京广播电视台卡酷少儿（卫视）
        "西宁" to "青海", "海东" to "青海", "格尔木" to "青海", "德令哈" to "青海", "玉树" to "青海",
        "银川" to "宁夏", "石嘴山" to "宁夏", "吴忠" to "宁夏", "固原" to "宁夏", "中卫" to "宁夏",
        "灵武" to "宁夏", "青铜峡" to "宁夏", "永宁" to "宁夏", "贺兰" to "宁夏", "同心" to "宁夏",
        "乌鲁木齐" to "新疆", "克拉玛依" to "新疆", "吐鲁番" to "新疆", "哈密" to "新疆", "库尔勒" to "新疆",
        "伊犁" to "新疆", "中国蓝" to "浙江", "快乐垂钓" to "湖南",
        "阿克苏" to "新疆", "喀什" to "新疆", "伊犁" to "新疆", "石河子" to "新疆", "昌吉" to "新疆",
        "呼和浩特" to "内蒙古", "包头" to "内蒙古", "乌海" to "内蒙古", "赤峰" to "内蒙古", "通辽" to "内蒙古",
        "鄂尔多斯" to "内蒙古", "呼伦贝尔" to "内蒙古", "巴彦淖尔" to "内蒙古", "乌兰察布" to "内蒙古",
        "锡林郭勒" to "内蒙古", "兴安" to "内蒙古", "阿拉善" to "内蒙古", "满洲里" to "内蒙古", "二连浩特" to "内蒙古",
        "拉萨" to "西藏", "日喀则" to "西藏", "昌都" to "西藏", "林芝" to "西藏", "山南" to "西藏",
        "那曲" to "西藏", "阿里" to "西藏", "墨脱" to "西藏", "亚东" to "西藏", "米林" to "西藏",
        "台北" to "台湾", "高雄" to "台湾", "台中" to "台湾", "台南" to "台湾", "新北" to "台湾",
        "基隆" to "台湾", "新竹" to "台湾", "嘉义" to "台湾", "彰化" to "台湾", "屏东" to "台湾",
        "花莲" to "台湾", "台东" to "台湾", "云林" to "台湾", "南投" to "台湾", "宜兰" to "台湾",
        "香港" to "香港", "九龙" to "香港", "新界" to "香港", "澳门" to "澳门", "氹仔" to "澳门"
    )

    /** 国家/地区关键词（中文 + 英文 + 常见海外频道品牌），顺序即分组排序 */
    private val COUNTRY_KEYWORDS = linkedMapOf(
        "美国" to listOf("美国", "北美", "usa", "america", "cnn", "fox news", "abc news", "cbs", "nbc",
            "hbo", "espn", "cnbc", "bloomberg", "discovery", "national geographic", "nat geo",
            "animal planet", "tlc", "mtv", "vh1", "paramount", "hgtv", "food network", "travel channel",
            "star movies", "cinemax", "amc", "tnt", "fx", "history channel", "a&e", "usa today", "wwe"),
        "英国" to listOf("英国", "uk", "britain", "bbc", "itv", "sky news", "channel 4", "channel 5",
            "eurosport uk", "talktv", "gb news", "freesports"),
        "法国" to listOf("法国", "france", "tv5", "france24", "tf1", "canal+", "france 2", "france 3", "arte"),
        "德国" to listOf("德国", "germany", "dw", "zdf", "ard", "rtl", "prosieben", "sat.1", "wdr", "ndr", "br fernsehen"),
        "意大利" to listOf("意大利", "italy", "rai", "mediaset", "canale 5", "italia 1", "rete 4", "sky italia"),
        "西班牙" to listOf("西班牙", "spain", "tve", "antena 3", "la sexta", "cuatro", "telecinco"),
        "葡萄牙" to listOf("葡萄牙", "portugal", "rtp", "sport tv"),
        "荷兰" to listOf("荷兰", "netherlands", "npo", "rtl4", "sbs6", "veronica"),
        "比利时" to listOf("比利时", "belgium", "rtbf", "vrt", "een"),
        "瑞士" to listOf("瑞士", "switzerland", "srf", "rsi"),
        "瑞典" to listOf("瑞典", "sweden", "svt", "tv4"),
        "挪威" to listOf("挪威", "norway", "nrk", "tv2"),
        "丹麦" to listOf("丹麦", "denmark", "dr1", "tv2"),
        "芬兰" to listOf("芬兰", "finland", "yle", "mtv3"),
        "冰岛" to listOf("冰岛", "iceland", "ruv"),
        "波兰" to listOf("波兰", "poland", "tvp", "polsat"),
        "捷克" to listOf("捷克", "czech", "ct1", "nova"),
        "匈牙利" to listOf("匈牙利", "hungary", "m1", "rtl klub"),
        "奥地利" to listOf("奥地利", "austria", "orf", "atv"),
        "希腊" to listOf("希腊", "greece", "ert", "mega"),
        "土耳其" to listOf("土耳其", "turkey", "trt", "istanbul"),
        "俄罗斯" to listOf("俄罗斯", "russia", "rt news", "russia today", "rtr", "1tv", "n tv", "russia 24"),
        "乌克兰" to listOf("乌克兰", "ukraine", "inter", "1+1", "ukraine 24"),
        "日本" to listOf("日本", "japan", "nhk", "tokyo", "东京", "大阪", "fuji", "tv asahi", "tv tokyo",
            "nippon", "ntv", "bs日", "wowow", "朝日", "日本テレビ"),
        "韩国" to listOf("韩国", "korea", "kbs", "mbc", "sbs", "arirang", "tvn", "jtbc", "ebs", "首尔",
            "kbs1", "kbs2", "mbc", "연합"),
        "新加坡" to listOf("新加坡", "singapore", "channel newsasia", "cna", "mediacorp", "starhub", "新传媒"),
        "马来西亚" to listOf("马来西亚", "malaysia", "astro", "rtm", "8tv", "tv3"),
        "泰国" to listOf("泰国", "thailand", "true", "channel 3", "thai", "曼谷", "bbtv", "workpoint"),
        "越南" to listOf("越南", "vietnam", "vtv", "vtc", "htv"),
        "柬埔寨" to listOf("柬埔寨", "cambodia", "ctn", "hang meas"),
        "老挝" to listOf("老挝", "laos", "lnb"),
        "缅甸" to listOf("缅甸", "myanmar", "mr tv", "mrtv"),
        "菲律宾" to listOf("菲律宾", "philippines", "abs-cbn", "gma", "abs cbn", "manila", "tv5 ph"),
        "印度尼西亚" to listOf("印度尼西亚", "印尼", "indonesia", "rcti", "mnctv", "trans7", "metro tv", "jakarta"),
        "印度" to listOf("印度", "india", "zee tv", "star plus", "ndtv", "doordarshan", "colors", "sony tv", "mumbai"),
        "巴基斯坦" to listOf("巴基斯坦", "pakistan", "ptv", "geo news", "ary"),
        "斯里兰卡" to listOf("斯里兰卡", "sri lanka", "derana", "rupavahini"),
        "尼泊尔" to listOf("尼泊尔", "nepal", "ntv"),
        "澳大利亚" to listOf("澳大利亚", "澳洲", "australia", "seven network", "nine network", "ten network", "channel 7", "channel 9", "channel 10", "sbs australia"),
        "新西兰" to listOf("新西兰", "new zealand", "tvnz", "maori tv"),
        "加拿大" to listOf("加拿大", "canada", "cbc", "toronto", "global news", "citytv"),
        "墨西哥" to listOf("墨西哥", "mexico", "televisa", "azteca"),
        "巴西" to listOf("巴西", "brazil", "globo", "record", "band", "sbt"),
        "阿根廷" to listOf("阿根廷", "argentina", "telefe", "el trece", "américa"),
        "智利" to listOf("智利", "chile", "chv", "13c", "tvn"),
        "秘鲁" to listOf("秘鲁", "peru", "atv", "panamericana"),
        "哥伦比亚" to listOf("哥伦比亚", "colombia", "caracol", "rcn", "citytv"),
        "古巴" to listOf("古巴", "cuba", "cubavision"),
        "南非" to listOf("南非", "south africa", "sabc", "dstv", "etv"),
        "埃及" to listOf("埃及", "egypt", "ona", "dmc", "cairo"),
        "尼日利亚" to listOf("尼日利亚", "nigeria", "channels tv", "nigerian"),
        "肯尼亚" to listOf("肯尼亚", "kenya", "ntv kenya"),
        "摩洛哥" to listOf("摩洛哥", "morocco", "2m", "al aoula"),
        "以色列" to listOf("以色列", "israel", "i24news", "keshet", "reshet"),
        "伊朗" to listOf("伊朗", "iran", "irib", "press tv"),
        "伊拉克" to listOf("伊拉克", "iraq", "al sharqiya"),
        "沙特阿拉伯" to listOf("沙特", "saudi", "mbc", "al arabiya", "rotana"),
        "阿联酋" to listOf("阿联酋", "迪拜", "dubai", "emirates", "abudhabi", "阿布扎比"),
        "卡塔尔" to listOf("卡塔尔", "qatar", "al jazeera", "beinsports", "bein"),
        "科威特" to listOf("科威特", "kuwait"),
        "约旦" to listOf("约旦", "jordan"),
        "黎巴嫩" to listOf("黎巴嫩", "lebanon", "lbci"),
        "蒙古" to listOf("蒙古", "mongolia", "mnb"),
        "哈萨克斯坦" to listOf("哈萨克斯坦", "kazakhstan", "qazaqstan"),
        "乌兹别克斯坦" to listOf("乌兹别克斯坦", "uzbekistan", "uztv"),
        "格鲁吉亚" to listOf("格鲁吉亚", "georgia", "gpb"),
        "亚美尼亚" to listOf("亚美尼亚", "armenia", "armtv"),
        "阿塞拜疆" to listOf("阿塞拜疆", "azerbaijan", "aztv"),
        "立陶宛" to listOf("立陶宛", "lithuania", "lrt"),
        "拉脱维亚" to listOf("拉脱维亚", "latvia", "ltv"),
        "爱沙尼亚" to listOf("爱沙尼亚", "estonia", "err"),
        "克罗地亚" to listOf("克罗地亚", "croatia", "hrt"),
        "塞尔维亚" to listOf("塞尔维亚", "serbia", "rts"),
        "罗马尼亚" to listOf("罗马尼亚", "romania", "tvr", "antena"),
        "保加利亚" to listOf("保加利亚", "bulgaria", "bnt"),
        "斯洛伐克" to listOf("斯洛伐克", "slovakia", "stv"),
        "斯洛文尼亚" to listOf("斯洛文尼亚", "slovenia", "rts slo"),
        "卢森堡" to listOf("卢森堡", "luxembourg", "rtl lux"),
        "马耳他" to listOf("马耳他", "malta", "tvm"),
       "塞浦路斯" to listOf("塞浦路斯", "cyprus", "cybc")
   )

   /** 国家/地区名列表（按 COUNTRY_KEYWORDS 声明顺序），用于分组排序 */
   private val COUNTRIES = COUNTRY_KEYWORDS.keys.toList()

    /** 英文/拼音频道名 → 中文规范名（键为 normalizeName 后的紧凑小写形式） */
    private val ALIASES: Map<String, String> by lazy {
        mapOf(
            "Hunan TV" to "湖南卫视", "Hunan Satellite" to "湖南卫视", "HunanTV" to "湖南卫视",
            "Zhejiang TV" to "浙江卫视", "ZJTV" to "浙江卫视",
            "Jiangsu TV" to "江苏卫视", "JSTV" to "江苏卫视",
            "Dragon TV" to "东方卫视", "Dongfang TV" to "东方卫视",
            "Guangdong TV" to "广东卫视", "GDTV" to "广东卫视",
            "Beijing TV" to "北京卫视",
            "BRTV 北京卫视" to "北京卫视",
            "Shandong TV" to "山东卫视", "SDTV" to "山东卫视",
            "Sichuan TV" to "四川卫视", "SCTV" to "四川卫视",
            "Hubei TV" to "湖北卫视", "Hubei Satellite" to "湖北卫视",
            "Henan TV" to "河南卫视", "HNTV" to "河南卫视",
            "Shenzhen TV" to "深圳卫视",
            "Tianjin TV" to "天津卫视",
            "Liaoning TV" to "辽宁卫视",
            "Heilongjiang TV" to "黑龙江卫视",
            "Jilin TV" to "吉林卫视",
            "Anhui TV" to "安徽卫视", "AHTV" to "安徽卫视",
            "Jiangxi TV" to "江西卫视",
            "Yunnan TV" to "云南卫视",
            "Guizhou TV" to "贵州卫视",
            "Gansu TV" to "甘肃卫视",
            "Qinghai TV" to "青海卫视",
            "Hainan TV" to "海南卫视",
            "Guangxi TV" to "广西卫视",
            "Chongqing TV" to "重庆卫视",
            "Hebei TV" to "河北卫视",
            "Inner Mongolia TV" to "内蒙古卫视", "NMGTV" to "内蒙古卫视",
            "Tibet TV" to "西藏卫视",
            "Ningxia TV" to "宁夏卫视",
            "Xinjiang TV" to "新疆卫视",
            "Southeast TV" to "东南卫视",
            "Xiamen TV" to "厦门卫视",
            "TVB Jade" to "翡翠台", "Jade Channel" to "翡翠台",
            "TVB Pearl" to "明珠台", "Pearl Channel" to "明珠台",
            "TVB News" to "无线新闻", "Cable News HK" to "有线新闻"
        ).mapKeys { (k, _) -> normalizeName(k) }
    }
}
