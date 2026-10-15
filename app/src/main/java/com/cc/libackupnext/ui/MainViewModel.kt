package com.cc.libackupnext.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.service.impl.AppRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @author cong
 * @date 2026/10/15
 */

enum class SortType { NAME, INSTALL_TIME, UPDATE_TIME }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppRepositoryImpl()

    private val _allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val allApps: StateFlow<List<AppInfo>> = _allApps.asStateFlow()

    private val _displayApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val displayApps: StateFlow<List<AppInfo>> = _displayApps.asStateFlow()

    // UI 状态
    var isSearching = MutableStateFlow(false)
    var searchQuery = MutableStateFlow("")
    var isSortMenuExpanded = MutableStateFlow(false)
    var isMoreMenuExpanded = MutableStateFlow(false)

    // 排序与过滤状态
    var sortType = MutableStateFlow(SortType.NAME)
    var isReverse = MutableStateFlow(false)
    var showSystemApps = MutableStateFlow(false)

    init {
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                repository.getInstalledApps(getApplication(), showSystemApps.value)
            }
            _allApps.value = apps
            applyFiltersAndSort()
        }
    }

    fun applyFiltersAndSort() {
        var list = _allApps.value

        // 排序
        list = when (sortType.value) {
            SortType.NAME -> list.sortedBy { it.name.lowercase() }
            SortType.INSTALL_TIME -> list.sortedBy { it.installTime }
            SortType.UPDATE_TIME -> list.sortedBy { it.updateTime }
        }

        if (isReverse.value) {
            list = list.reversed()
        }

        // 搜索过滤
        if (searchQuery.value.isNotBlank()) {
            list = list.filter {
                it.name.contains(searchQuery.value, ignoreCase = true) ||
                        it.packageName.contains(searchQuery.value, ignoreCase = true)
            }
        }

        _displayApps.value = list
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
        applyFiltersAndSort()
    }

    fun toggleSearch() {
        isSearching.value = !isSearching.value
        if (!isSearching.value) {
            searchQuery.value = ""
            applyFiltersAndSort()
        }
    }
}