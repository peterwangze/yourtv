package com.horsenma.yourtv.models

import android.os.Looper
import androidx.lifecycle.MutableLiveData

/**
 * 跨线程安全的 LiveData 写入：
 * - 主线程调用时走 setValue（同步、立即生效），保持原有语义；
 * - 后台线程调用时走 postValue（异步投递到主线程），避免
 *   "Cannot invoke setValue on a background thread" 崩溃。
 */
fun <T> MutableLiveData<T>.setValueSafe(value: T) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        setValue(value)
    } else {
        postValue(value)
    }
}
