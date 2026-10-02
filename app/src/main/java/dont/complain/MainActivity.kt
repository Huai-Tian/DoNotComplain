package dont.complain

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dont.complain.ui.theme.DoNotComplainTheme
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ModuleService.ensureRegistered()
        setContent {
            DoNotComplainTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ModuleScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * 桥接 libxposed 服务。框架守护进程在模块进程存活期间推送 binder，
 * registerListener 每个进程只能调用一次。
 */
object ModuleService {
    var service by mutableStateOf<XposedService?>(null)
        private set

    private var registered = false

    fun ensureRegistered() {
        if (registered) return
        registered = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                this@ModuleService.service = service
            }

            override fun onServiceDied(service: XposedService) {
                if (this@ModuleService.service === service) {
                    this@ModuleService.service = null
                }
            }
        })
    }
}

@Composable
fun ModuleScreen(modifier: Modifier = Modifier) {
    val service = ModuleService.service
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall
        )
        if (service == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.status_not_activated),
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            StatusCard(service)
            SwitchCard(service)
        }
        Text(
            text = stringResource(R.string.hint_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.hint_body),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun StatusCard(service: XposedService) {
    val frameworkName = remember(service) {
        runCatching { service.frameworkName }.getOrDefault("")
    }
    val frameworkVersion = remember(service) {
        runCatching { service.frameworkVersion }.getOrDefault("")
    }
    val properties = remember(service) {
        runCatching { service.frameworkProperties }.getOrDefault(0L)
    }
    val scope = remember(service) {
        runCatching { service.scope }.getOrDefault(emptyList())
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(stringResource(R.string.framework_fmt, frameworkName, frameworkVersion))
            Text(stringResource(R.string.scope_fmt, scope.joinToString(", ")))
            if (properties and XposedService.PROP_CAP_SYSTEM == 0L) {
                Text(
                    text = stringResource(R.string.no_system_cap),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SwitchCard(service: XposedService) {
    val prefs = remember(service) {
        runCatching { service.getRemotePreferences(Config.PREFS_GROUP) }.getOrNull()
    }
    var enabled by remember(prefs) {
        mutableStateOf(prefs?.getBoolean(Config.KEY_ENABLED, true) ?: true)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.master_switch_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.master_switch_subtitle),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    prefs?.edit()?.putBoolean(Config.KEY_ENABLED, checked)?.apply()
                }
            )
        }
    }
}
