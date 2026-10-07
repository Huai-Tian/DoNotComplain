package io.github.huai_tian.donotcomplain.hook

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Binder
import android.os.Parcel
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * system_server 内的通知权限查询欺骗。
 *
 * 分层设计（DFS 式多机型适配）：每层独立 try-catch、独立降级，任一层缺失只损失
 * 该层覆盖；匹配按"方法名 + 参数形态 + 参数扫描"而非写死签名位置——AIDL 参数
 * 在各版本/OEM 间漂移（T: checkUidPermission(String,int,int)；15: (int,String,int)），
 * 扫描式匹配对漂移免疫。某层零命中时自动 dump 目标类的方法签名到日志，
 * 供新机型 bring-up（用户回报日志即可定位该 ROM 的真实符号）。
 *
 * 安全不变量（与 AOSP 8.1/13/15 源码核对）：
 * - 只 hook 查询路径；投递裁决（enqueueNotification → *Int 私有方法 →
 *   PermissionHelper.hasPermission，全程 clearCallingIdentity，calling=1000）
 *   与谎言条件（calling == 被查目标）结构性隔离，永不被骗；
 * - 仅当调用方查询自身（uid 或包名匹配 calling）时说谎；系统侧查询恒真值；
 * - Toast 投递路径（checkCanEnqueueToast → 公有 are*）按栈帧识别并放行真值。
 */
object SystemHooker {

    private const val TAG = "DoNotComplain"

    /** android.app.AppOpsManager#OP_POST_NOTIFICATION，API 19 起恒为 11 */
    private const val OP_POST_NOTIFICATION = 11

    /** AppOpsManager#MODE_ALLOWED == PackageManager#PERMISSION_GRANTED */
    private const val ALLOWED = 0

    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    // ------------------------------------------------------------------
    // 类候选表（跨版本 / 跨 OEM 探测）
    // ------------------------------------------------------------------

    private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

    private val APP_OPS_CLASS_CANDIDATES = listOf(
        "com.android.server.appop.AppOpsService", // Android 11+
        "android.app.AppOpsService",              // Android 9/10
        "com.android.server.AppOpsService",       // Android 8.x
    )

    private val PMS_CLASS_CANDIDATES = listOf(
        "com.android.server.pm.permission.PermissionManagerService",     // binder Stub（T/15）
        "com.android.server.pm.permission.PermissionManagerServiceImpl", // 15 的实现类
    )

    private const val AMS_CLASS = "com.android.server.am.ActivityManagerService"

    /**
     * Layer E: ColorOS 专有通知 API（OplusNotificationManager 的服务端实现）。
     * 抖音判例：其"开启通知"检测在 ColorOS 上反射 android.app.OplusNotificationManager，
     * 调 getStowOption/getAppVisibility/getBadgeOption/getAppBanner/
     * isAppRingtonePermissionGranted/isAppVibrationPermissionGranted 等专有方法——
     * 完全绕过 AOSP 标准路径（areNotificationsEnabled / checkOp / POST_NOTIFICATIONS）。
     * 客户端类在 android.app 包，binder 进入 NMS/通知服务的 Oplus 扩展；
     * 服务端方法名与客户端一致（binder 惯例），在 NMS 及其嵌套类中按名扫描。
     */
    private val OPLUS_QUERY_METHOD_NAMES = setOf(
        "getStowOption", "getAppVisibility", "getBadgeOption", "getAppBanner",
        "isAppRingtonePermissionGranted", "isAppVibrationPermissionGranted",
    )

    /**
     * 已注册 hook 的 id 集合（id = executable.toGenericString()）。
     * 热重载时重装同 id 的 hook 会原子替换；入口类据此卸载未被重建的旧 hook。
     */
    val hookedIds = mutableSetOf<String>()

    private val uidPackages = ConcurrentHashMap<Int, Set<String>>()

    /** 无配置设计：LSPosed 启用开关即唯一开关，启用 = 欺骗全部应用的自身查询 */
    fun install(xposed: XposedInterface, classLoader: ClassLoader) {
        xposedRef = xposed
        var count = 0
        count += runLayer(xposed, "AppOps") { hookAppOpsService(it, classLoader) }
        count += runLayer(xposed, "NMS") { hookNotificationManagerService(it, classLoader) }
        count += runLayer(xposed, "PMS") { hookPermissionManagerService(it, classLoader) }
        count += runLayer(xposed, "AMS") { hookActivityManagerService(it, classLoader) }
        count += runLayer(xposed, "Oplus") { hookOplusNotification(xposed, classLoader) }
        hookServiceRegistryDiag(xposed, classLoader) // 诊断层，不计入 hook 总数
        xposed.log(Log.INFO, TAG, "Installed $count hooks in system_server")
    }

    /** 供 logOnce 使用的框架日志接口（热重载后由新代重设） */
    @Volatile
    private var xposedRef: XposedInterface? = null

    private inline fun runLayer(
        xposed: XposedInterface, name: String, block: (XposedInterface) -> Int
    ): Int = runCatching { block(xposed) }.getOrElse {
        xposed.log(Log.ERROR, TAG, "Layer $name failed", it); 0
    }.also { xposed.log(Log.INFO, TAG, "Layer $name: $it hooks") }

    // ------------------------------------------------------------------
    // Layer A: AppOpsService（应用直接探测 OP_POST_NOTIFICATION）
    // ------------------------------------------------------------------

    private fun hookAppOpsService(xposed: XposedInterface, cl: ClassLoader): Int {
        for (name in APP_OPS_CLASS_CANDIDATES) {
            val clazz = load(cl, name, xposed) ?: continue
            var count = 0
            for (method in clazz.declaredMethods) {
                // checkOperation(int code, int uid, String pkg[, ...]) -> int
                // AIDL 参数顺序稳定（code/uid/pkg 在前，附加参数在后）
                if (method.name in CHECK_OPERATION_NAMES && method.parameterCount in 3..5
                    && method.parameterTypes[0] == Integer.TYPE
                    && method.parameterTypes[1] == Integer.TYPE
                    && method.returnType == Integer.TYPE
                ) {
                    if (hookSafely(xposed, method, appOpsHooker)) count++
                }
            }
            if (count > 0) return count
        }
        xposed.log(Log.WARN, TAG, "AppOpsService#checkOperation not found")
        return 0
    }

    private val CHECK_OPERATION_NAMES = setOf("checkOperation", "checkOperationRaw")

    /**
     * 应用查询自身 OP_POST_NOTIFICATION 时返回 MODE_ALLOWED。
     *
     * 13 之前 NMS 投递会以应用身份内部调用 checkOpNoThrow——调用栈含
     * com.android.server.* 帧时放行真实结果（查询被欺骗、投递不受影响）。
     */
    private val appOpsHooker: Hooker = Hooker { chain ->
        val args = chain.args
        val code = args.getOrNull(0) as? Int
        val uid = args.getOrNull(1) as? Int
        if (code == OP_POST_NOTIFICATION && uid != null
            && Binder.getCallingUid() == uid && !isSystemInternalCall(chain)
        ) {
            ALLOWED
        } else {
            chain.proceed()
        }
    }

    // ------------------------------------------------------------------
    // Layer B: NotificationManagerService（are* 总开关 + 渠道查询）
    // ------------------------------------------------------------------

    private fun hookNotificationManagerService(xposed: XposedInterface, cl: ClassLoader): Int {
        val nms = load(cl, NMS_CLASS, xposed) ?: return 0
        val classes = collectSelfAndNested(nms, depth = 2)
        var boolCount = 0
        var channelCount = 0
        for (clazz in classes) {
            for (method in clazz.declaredMethods) {
                if (Modifier.isAbstract(method.modifiers)) continue
                val name = method.name
                when {
                    ARE_ENABLED_NAMES.any { name == it || name == it + "Int" } -> {
                        // 形态漂移容忍：1 参（13+ 客户端 binder 入口）到 5 参（OEM 附加
                        // userId/deviceId 等）；ColorOS 判例：binder 名带 Int 后缀。
                        // 自查判定按参数扫描（见 hooker）。
                        if (method.parameterCount in 1..5
                            && method.returnType == java.lang.Boolean.TYPE
                        ) {
                            if (hookSafely(xposed, method, nmsBoolHooker)) boolCount++
                        }
                    }
                    name == "getNotificationChannel" || name == "getNotificationChannels"
                            || name == "getNotificationChannelInt" -> {
                        if (method.parameterCount in 1..6) {
                            if (hookSafely(xposed, method, channelHooker)) channelCount++
                        }
                    }
                }
            }
        }
        if (boolCount + channelCount == 0) dumpMethods(xposed, classes, "areNotif", "Channel")
        return boolCount + channelCount
    }

    private val ARE_ENABLED_NAMES = setOf(
        "areNotificationsEnabled",
        "areNotificationsEnabledForPackage",
        "areNotificationsEnabledForChannel",
        "areChannelsEnabled",
    )

    /**
     * 应用查询自身总开关 / 渠道开关时返回 true。
     *
     * 自查判定（参数扫描，对 OEM 的 uid/userId 形态漂移免疫）：
     * - uid 匹配：任一 int 参数 == calling uid；
     * - 包名匹配：任一 String 参数 ∈ calling uid 的包集合（覆盖 (pkg, userId) 形态）。
     *
     * Toast 投递路径（checkCanEnqueueToast → 公有 are*）按栈帧放行真值。
     */
    private val nmsBoolHooker: Hooker = Hooker { chain ->
        val args = chain.args
        val callingUid = Binder.getCallingUid()
        val uidMatch = args.any { it is Int && it == callingUid }
        val self = uidMatch || run {
            val pkgs = packagesForUid(callingUid)
            args.any { it is String && it in pkgs }
        }
        if (self && !isToastEnqueueCall()) {
            true
        } else {
            chain.proceed()
        }
    }

    // ------------------------------------------------------------------
    // Layer C: PermissionManagerService（13+ 运行时权限的 binder 实现）
    // ------------------------------------------------------------------

    private fun hookPermissionManagerService(xposed: XposedInterface, cl: ClassLoader): Int {
        var total = 0
        var matchedClasses = 0
        for (name in PMS_CLASS_CANDIDATES) {
            val clazz = load(cl, name, xposed) ?: continue
            matchedClasses++
            var count = 0
            for (candidate in collectSelfAndNested(clazz, depth = 1)) {
                for (method in candidate.declaredMethods) {
                    if (Modifier.isAbstract(method.modifiers)) continue
                    if (method.name in PERMISSION_CHECK_NAMES && method.parameterCount in 2..5
                        && method.parameterTypes.any { it == String::class.java }
                        && method.returnType == Integer.TYPE
                    ) {
                        if (hookSafely(xposed, method, pmsHooker)) count++
                    }
                }
            }
            xposed.log(Log.INFO, TAG, "$name: $count hooks")
            total += count
        }
        if (total == 0 && matchedClasses > 0) {
            dumpMethods(
                xposed,
                PMS_CLASS_CANDIDATES.mapNotNull { runCatching { cl.loadClass(it) }.getOrNull() },
                "checkPermission", "checkUidPermission"
            )
        }
        return total
    }

    private val PERMISSION_CHECK_NAMES = setOf("checkPermission", "checkUidPermission")

    /**
     * PMS 侧自查判定（按方法族区分，避免 pid/userId/deviceId 的数值误配）：
     * - checkUidPermission 族：任一 int 参数 == calling uid——userId/deviceId 恒为
     *   小数值（< 10000），不可能与 app uid（≥ 10000）数值碰撞；
     * - checkPermission 族：任一 String 参数 ∈ calling uid 的包集合（权限名不会
     *   出现在包集合中，天然排除）。
     *
     * 投递安全：NMS 投递裁决经 PermissionHelper.hasPermission 前置
     * clearCallingIdentity（calling=1000 ≠ app uid）→ 恒真值。
     */
    private val pmsHooker: Hooker = Hooker { chain ->
        val args = chain.args
        if (args.none { it == POST_NOTIFICATIONS }) {
            chain.proceed()
        } else {
            val self = if (chain.executable.name == "checkUidPermission") {
                val callingUid = Binder.getCallingUid()
                args.any { it is Int && it == callingUid }
            } else {
                val pkgs = packagesForUid(Binder.getCallingUid())
                args.any { it is String && it in pkgs }
            }
            if (self) ALLOWED else chain.proceed()
        }
    }

    // ------------------------------------------------------------------
    // Layer D: ActivityManagerService（15 上应用侧 checkSelfPermission 的汇聚点）
    // ------------------------------------------------------------------

    private fun hookActivityManagerService(xposed: XposedInterface, cl: ClassLoader): Int {
        val ams = load(cl, AMS_CLASS, xposed) ?: return 0
        var count = 0
        for (clazz in collectSelfAndNested(ams, depth = 1)) {
            for (method in clazz.declaredMethods) {
                // checkPermission(String permission, int pid, int uid) -> int
                if (method.name == "checkPermission" && method.parameterCount in 3..4
                    && method.parameterTypes[0] == String::class.java
                    && method.returnType == Integer.TYPE
                ) {
                    if (hookSafely(xposed, method, amsHooker)) count++
                }
            }
        }
        if (count == 0) dumpMethods(xposed, listOf(ams), "checkPermission")
        return count
    }

    /**
     * 应用侧 checkSelfPermission(POST_NOTIFICATIONS) 的欺骗点（DFS 同款 hook）。
     * 15 上 PermissionManager 本地缓存 miss 后经 checkPermissionUncached 汇入此处；
     * NMS 投递裁决（PermissionHelper，clearCallingIdentity 后 calling=1000）也经
     * 此处但 uid 参数为 app uid——与 calling 不等 → 恒真值。
     * uid 取最后一个 int 参数（AIDL 顺序 pid 在前 uid 在后，跨版本稳定）。
     */
    private val amsHooker: Hooker = Hooker { chain ->
        val args = chain.args
        val perm = args.getOrNull(0) as? String
        if (perm != POST_NOTIFICATIONS) {
            chain.proceed()
        } else {
            val uid = args.lastOrNull { it is Int } as? Int
            if (uid != null && Binder.getCallingUid() == uid) {
                ALLOWED
            } else {
                chain.proceed()
            }
        }
    }

    // ------------------------------------------------------------------
    // Layer E: ColorOS 专有通知 API
    // ------------------------------------------------------------------

    /**
     * Layer E 探测（真机判例修正）：服务端宿主是独立的 ExtImpl 类
     * （ColorOS 15 实测：OplusNotificationManagerServiceExtImpl 在
     * system_server 内运行，非 NMS 嵌套类）。按 OPLUS 扩展类的命名规律
     * 给出候选表，另保留 NMS 嵌套扫描作兜底。**OEM 门控**：探测
     * android.app.OplusNotificationManager（boot classpath）不存在即整层跳过。
     */
    private val OPLUS_SERVICE_CLASS_CANDIDATES = listOf(
        "com.android.server.notification.OplusNotificationManagerServiceExtImpl", // ColorOS 15 实测
        "com.oplus.notification.OplusNotificationManagerServiceExtImpl",
        "com.android.server.notification.OplusNotificationManagerService",
    )

    @SuppressLint("PrivateApi")
    private fun hookOplusNotification(xposed: XposedInterface, cl: ClassLoader): Int {
        val marker = runCatching { cl.loadClass("android.app.OplusNotificationManager") }.getOrNull()
        if (marker == null) {
            xposed.log(Log.INFO, TAG, "Layer Oplus: skipped (not ColorOS)")
            return 0
        }
        // 加固（用户拍板）：接口契约匹配——枚举 IOplusNotificationManager 的全部
        // AIDL 方法（含未来新增），扫描 NMS 全嵌套（含匿名类）中实现该接口的类，
        // 按契约 hook——不再依赖 6 个硬编码方法名，堵住"服务端策略改用四个 int
        // 方法判定"的复活路径，FeatureSwitchReport 上报也变全开假象。
        val ifaces = marker.declaredMethods
            .mapNotNull { it.returnType.takeIf { t -> t.isInterface && t.simpleName.startsWith("I") } }
            .distinct()
        val contract = mutableListOf<Method>()
        for (iface in ifaces) {
            runCatching {
                for (m in iface.methods) {
                    // 只钩查询（get/is/should/can 前缀，boolean/int 返回）；
                    // set/clear 前缀是系统设置写路径，动它会影响 ColorOS 设置界面
                    if (m.parameterCount == 2 && m.returnType in setOf(
                            java.lang.Boolean.TYPE, Integer.TYPE
                        ) && (m.name.startsWith("get") || m.name.startsWith("is") ||
                                m.name.startsWith("should") || m.name.startsWith("can"))
                    ) {
                        contract.add(m)
                        xposed.log(Log.INFO, TAG, "oplus-contract: ${iface.name}#${m.name}(${m.parameterTypes.joinToString { it.simpleName }})→${m.returnType.simpleName}")
                    }
                }
            }
        }
        val nms = runCatching { cl.loadClass(NMS_CLASS) }.getOrNull()
        val searchSpace = mutableListOf<Class<*>>()
        for (name in OPLUS_SERVICE_CLASS_CANDIDATES) {
            runCatching { cl.loadClass(name) }.getOrNull()?.let {
                searchSpace.addAll(collectSelfAndNested(it, depth = 2))
            }
        }
        if (nms != null) searchSpace.addAll(collectSelfAndNested(nms, depth = 4))
        xposed.log(Log.INFO, TAG, "Layer Oplus: scanning ${searchSpace.size} classes, contract=${contract.map { it.name }}")
        var count = 0
        val hooked = mutableListOf<String>()
        val contractNames = contract.map { it.name }.toSet()
        for (clazz in searchSpace) {
            // 类级过滤（v10.1 修正）：沿整条 superclass 链查接口——binder Stub 的
            // 形态是 NMS$12 extends IOplusNotificationManager$Stub extends Binder
            // implements IOplus...，接口在祖父级；只查一层会漏（v10 判例：0 命中）。
            val implementsOplus = implementsAnyIface(clazz, ifaces)
            // ExtImpl 路径独立于接口过滤（它实现 INotificationManagerServiceExt，
            // 服务端方法是 Inner 后缀变体——v9 真机判例）
            val isExtImpl = clazz.name.endsWith("ExtImpl")
            if (!implementsOplus && !isExtImpl) {
                // v10.1 诊断（判例驱动）：匿名 Stub 可能 extends AOSP Stub 而不
                // implements OPLUS 接口（扩展方法直接写在类体）。任何含契约方法名
                // 的类都 dump 谱系——一次日志定位真实结构。
                val hasContractMethod = clazz.declaredMethods.any { it.name in contractNames }
                if (hasContractMethod) {
                    xposed.log(Log.INFO, TAG, "diag-lineage: ${clazz.name} super=${lineage(clazz)} interfaces=${clazz.interfaces.joinToString { it.name }}")
                }
                continue
            }
            for (method in clazz.declaredMethods) {
                if (Modifier.isAbstract(method.modifiers)) continue
                val name = method.name
                if (contractNames.contains(name) || OPLUS_QUERY_METHOD_NAMES.any { name == it || name == it + "Inner" }) {
                    if (hookSafely(xposed, method, oplusHooker)) {
                        count++
                        hooked.add("${clazz.simpleName}#$name")
                    }
                }
            }
        }
        xposed.log(Log.INFO, TAG, "Layer Oplus hooks($count): $hooked")
        if (count == 0) {
            dumpMethods(xposed, searchSpace, "Stow", "Badge", "Banner", "Ringtone", "Vibration", "Visibility")
        }
        return count
    }

    /**
     * 全谱系接口扫描：类自身及全部父类的直接接口里，是否有目标接口本身或其子接口。
     * `t.isAssignableFrom(i)` ≡ i 等于 t 或继承自 t——等价于手写递归展开接口树，
     * 但走 JDK 原语（无递归栈，native 实现）。
     */
    private fun implementsAnyIface(clazz: Class<*>, targets: List<Class<*>>): Boolean {
        var c: Class<*>? = clazz
        while (c != null) {
            if (c.interfaces.any { i -> targets.any { t -> t.isAssignableFrom(i) } }) return true
            c = c.superclass
        }
        return false
    }

    /** 类的父类链（诊断用）：NMS$12 → INotificationManager$Stub → Binder → Object */
    private fun lineage(clazz: Class<*>): String {
        val chain = mutableListOf<String>()
        var c: Class<*>? = clazz.superclass
        while (c != null && chain.size < 5) {
            chain.add(c.name)
            c = c.superclass
        }
        return chain.joinToString(" → ")
    }

    // ------------------------------------------------------------------
    // Layer G: 服务注册诊断（ColorOS 通知服务 Stub 定位）
    // ------------------------------------------------------------------

    /** 候选服务名探测：system_server 内 getService 返回本地 Stub 实例，其类名即服务端实现 */
    private val SERVICE_PROBE_NAMES = listOf(
        "notification", "oplus_notification", "oplus_notificationmanager",
        "notification_manager", "oplus_notification_manager", "oppo_notification",
    )

    /** 观察 ServiceManager.addService：记录所有服务注册（name → binder 实现类） */
    @SuppressLint("PrivateApi")
    private fun hookServiceRegistryDiag(xposed: XposedInterface, cl: ClassLoader): Int {
        // 1) 探测已注册的候选服务
        runCatching {
            val sm = cl.loadClass("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            for (name in SERVICE_PROBE_NAMES) {
                val binder = runCatching { getService.invoke(null, name) }.getOrNull()
                if (binder != null) {
                    xposed.log(Log.INFO, TAG, "svc-probe: $name -> ${binder.javaClass.name}")
                }
            }
        }
        // 2) 观察后续注册（本模块加载后的）
        val sm = runCatching { cl.loadClass("android.os.ServiceManager") }.getOrNull() ?: return 0
        var count = 0
        for (m in sm.declaredMethods) {
            if (m.name == "addService" && m.parameterCount >= 2
                && m.parameterTypes[0] == String::class.java
            ) {
                if (hookSafely(xposed, m, addServiceObserver)) count++
            }
        }
        xposed.log(Log.INFO, TAG, "Layer SvcDiag: $count hooks (addService observer)")
        return count
    }

    private val addServiceObserver: Hooker = Hooker { chain ->
        runCatching {
            val name = chain.args.getOrNull(0) as? String
            val binder = chain.args.getOrNull(1)
            if (name != null && binder != null) {
                logOnce("svc-reg: $name -> ${binder.javaClass.name}")
            }
        }
        chain.proceed()
    }

    /**
     * Oplus 专有查询的欺骗：调用方查自身（包名/uid 匹配 calling）时，
     * boolean 方法返回 true、int 状态方法返回非零（开启态）。
     */
    private val oplusHooker: Hooker = Hooker { chain ->
        val callingUid = Binder.getCallingUid()
        val pkgs = packagesForUid(callingUid)
        val self = chain.args.any { arg ->
            (arg is String && arg in pkgs) || (arg is Int && arg == callingUid)
        }
        if (self) {
            when (chain.executable.let { it as? Method }?.returnType) {
                java.lang.Boolean.TYPE -> true
                Integer.TYPE -> 1
                else -> chain.proceed()
            }
        } else {
            chain.proceed()
        }
    }

    // ------------------------------------------------------------------
    // 通知渠道重要性
    // ------------------------------------------------------------------

    /**
     * getNotificationChannel(s)：应用查询自身渠道时，把返回结果中被用户关闭
     * （IMPORTANCE_NONE）的渠道改写为 IMPORTANCE_DEFAULT。
     *
     * 返回值可能是 NMS 内部的存储对象——必须克隆后修改再返回，否则会真实
     * 改动系统状态；列表场景只替换列表元素（列表本身是临时响应容器）。
     *
     * 防御性纪律（system_server 内 hooker 绝不向外抛异常）：整个改写包在
     * runCatching 里，失败即回退原始结果；只对确切的基类实例克隆改写（OEM
     * 子类的 parcel 布局与基类 CREATOR 不兼容是真实风险），失败经一次性
     * 诊断日志留痕。
     */
    private val channelHooker: Hooker = Hooker { chain ->
        val result = chain.proceed()
        if (!isSelfChannelQuery(chain)) return@Hooker result
        runCatching { rewriteChannels(result) }
            .onFailure {
                logOnce("channel rewrite failed: ${it.javaClass.simpleName}: ${it.message}")
            }
            .getOrDefault(result)
    }

    /** 一次性日志（冷路径：服务注册、渠道改写失败等）；仅 LSPosed 通道 */
    private val loggedOnce = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun logOnce(msg: String) {
        if (loggedOnce.add(msg)) {
            runCatching { xposedRef?.log(Log.WARN, TAG, msg) }
        }
    }

    private fun isSelfChannelQuery(chain: Chain): Boolean {
        val method = chain.executable
        val params = method.parameterTypes
        val stringPositions = params.indices.filter { params[it] == String::class.java }
        if (stringPositions.isEmpty()) return false
        // getNotificationChannel 的最后一个 String 参数是 channelId，不参与判断
        val checked = if (method.name == "getNotificationChannel") {
            stringPositions.dropLast(1)
        } else {
            stringPositions
        }
        if (checked.isEmpty()) return false
        val pkgs = packagesForUid(Binder.getCallingUid())
        return checked.all { idx ->
            val pkgArg = chain.args[idx] as? String
            pkgArg != null && pkgArg in pkgs
        }
    }

    private fun rewriteChannels(result: Any?): Any? {
        when (result) {
            is NotificationChannel -> return rewriteChannel(result)
            is List<*> -> rewriteChannelList(result)
            else -> if (result != null) rewriteParceledListSlice(result)
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun rewriteChannelList(list: List<*>) {
        for (i in list.indices) {
            val item = list[i]
            if (item is NotificationChannel) {
                val rewritten = rewriteChannel(item)
                if (rewritten !== item && list is MutableList<*>) {
                    runCatching { (list as MutableList<Any?>)[i] = rewritten }
                }
            }
        }
    }

    private fun rewriteParceledListSlice(slice: Any) {
        runCatching {
            val list = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: return
            rewriteChannelList(list)
        }
    }

    private fun rewriteChannel(channel: NotificationChannel): NotificationChannel {
        if (channel.importance != NotificationManager.IMPORTANCE_NONE) return channel
        // 防御：OEM 子类的 parcel 布局与基类 CREATOR 可能不兼容——只改写
        // 确切的基类实例，克隆后二次校验
        if (channel.javaClass != NotificationChannel::class.java) return channel
        val copy = cloneChannel(channel) ?: return channel
        if (copy.javaClass != NotificationChannel::class.java) return channel
        copy.importance = NotificationManager.IMPORTANCE_DEFAULT
        return copy
    }

    private fun cloneChannel(channel: NotificationChannel): NotificationChannel? = runCatching {
        val parcel = Parcel.obtain()
        try {
            channel.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            NotificationChannel.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** Toast 投递路径识别：栈中出现 Toast 相关的 NMS 帧即放行真值 */
    private fun isToastEnqueueCall(): Boolean =
        Thread.currentThread().stackTrace.any { it.methodName.contains("Toast") }

    private fun isSystemInternalCall(chain: Chain): Boolean {
        val self = chain.executable.declaringClass.name
        for (element in Thread.currentThread().stackTrace) {
            val cn = element.className
            if (cn == self || cn.startsWith("$self$")) continue
            if (cn.startsWith("com.android.server.")) return true
        }
        return false
    }

    private fun packagesForUid(uid: Int): Set<String> =
        uidPackages.computeIfAbsent(uid) { lookupPackagesForUid(it) }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun lookupPackagesForUid(uid: Int): Set<String> = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "package") as? android.os.IBinder ?: return emptySet()
        val stub = Class.forName($$"android.content.pm.IPackageManager$Stub")
        val pm = stub.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder)
        val packages = pm.javaClass
            .getMethod("getPackagesForUid", Integer.TYPE)
            .invoke(pm, uid) as? Array<*> ?: return emptySet()
        packages.filterIsInstance<String>().toSet()
    }.getOrDefault(emptySet())

    private fun load(cl: ClassLoader, name: String, xposed: XposedInterface): Class<*>? =
        runCatching { cl.loadClass(name) }.getOrNull().also {
            if (it == null) xposed.log(Log.WARN, TAG, "Class not found: $name")
        }

    private fun hookSafely(xposed: XposedInterface, method: Method, hooker: Hooker): Boolean =
        runCatching {
            val id = method.toGenericString()
            // 同 id 重复安装 = 原子替换（热重载的幂等基础）
            xposed.hook(method).setId(id).intercept(hooker)
            hookedIds.add(id)
            true
        }.getOrElse {
            xposed.log(Log.WARN, TAG, "Failed to hook ${method.declaringClass.name}#${method.name}", it)
            false
        }

    /**
     * 机型适配诊断：某层零命中时，把目标类中名字含关键词的方法签名打进日志。
     * 新机型 bring-up 流程：装模块 → 看本层日志 → 回报 dump 内容 → 按真实符号
     * 调整候选表/匹配规则。
     */
    private fun dumpMethods(
        xposed: XposedInterface,
        classes: List<Class<*>>,
        vararg keywords: String,
    ) {
        var dumped = 0
        outer@ for (clazz in classes) {
            for (method in clazz.declaredMethods) {
                val lower = method.name.lowercase()
                if (keywords.any { lower.contains(it.lowercase()) }) {
                    xposed.log(Log.INFO, TAG, "diag: ${method.toGenericString()}")
                    if (++dumped >= 30) break@outer
                }
            }
        }
        if (dumped == 0) xposed.log(Log.WARN, TAG, "diag: no candidate methods in ${classes.map { it.name }}")
    }

    private fun collectSelfAndNested(root: Class<*>, depth: Int): List<Class<*>> {
        val result = ArrayList<Class<*>>()
        result.add(root)
        if (depth > 0) {
            for (nested in root.declaredClasses) {
                result.addAll(collectSelfAndNested(nested, depth - 1))
            }
            // 匿名/局部类不在 declaredClasses（v8 判例：binder Stub 是 NMS$12
            // 匿名类）。经 getDeclaredClasses 拿不到——按命名约定逐个探测。
            var idx = 1
            while (idx < 200) {
                val anon = runCatching {
                    Class.forName($$"$${root.name}$$$idx", false, root.classLoader)
                }.getOrNull() ?: break
                result.add(anon)
                idx++
            }
        }
        return result
    }
}