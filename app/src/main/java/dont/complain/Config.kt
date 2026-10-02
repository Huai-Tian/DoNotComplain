package dont.complain

/**
 * 模块配置常量。hook 侧（system_server）与 UI 侧（模块应用）共用。
 */
object Config {
    /** RemotePreferences 分组名 */
    const val PREFS_GROUP = "config"

    /** 总开关，默认开启 */
    const val KEY_ENABLED = "enabled"
}
