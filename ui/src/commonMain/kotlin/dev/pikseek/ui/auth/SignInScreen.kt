package dev.pikseek.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.net.ProxySetting
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.screens.settings.Avatar
import dev.piko.ui.screens.settings.ProxySettingsDialog
import dev.pikseek.auth.AuthNetworkPolicy
import dev.pikseek.auth.KnownAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 登录页。密码只在这一页的输入框里停留：点「登录」的那一刻取出来、清空输入框、交给认证模块，
 * 这一页与它的状态里都不再有它。
 *
 * 宽窗口左右分栏，左边是品牌，右边是表单。本机保存着账号时列在表单之前，点一下直接进；
 * 会话失效的填好账号名、光标落到密码框。登录着一个账号再加一个时（[onCancel] 不为 null）不列它们。
 */
@Composable
fun SignInScreen(
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clientManager = LocalPikoServices.current.clientManager
    val preferences = LocalPikoServices.current.preferences
    val state = remember { SignInState(clientManager, scope, clientManager.addingPrefill) }
    val known by clientManager.accounts.collectAsState()
    val proxySetting by preferences.proxySettingFlow.collectAsState(ProxySetting())
    var showProxyDialog by remember { mutableStateOf(false) }
    if (onCancel != null) BackHandler(onBack = onCancel)

    val wide = currentWidthClass() == WidthClass.Expanded
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (wide) BrandPane(Modifier.weight(1f).fillMaxHeight())
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                SignInForm(
                    state = state,
                    saved = if (onCancel == null) known.accounts else emptyList(),
                    adding = onCancel != null,
                    showLogo = !wide,
                    proxySummary = proxySetting.summary(),
                    onOpenProxy = { showProxyDialog = true },
                    modifier = Modifier.align(Alignment.Center),
                )
                if (onCancel != null) {
                    TooltipIconButton(
                        Icons.Outlined.Close,
                        "取消",
                        onClick = onCancel,
                        enabled = !state.isSigningIn,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    )
                }
            }
        }
    }

    if (showProxyDialog) {
        ProxySettingsDialog(
            current = proxySetting,
            onSave = { setting ->
                showProxyDialog = false
                scope.launch { preferences.saveProxySetting(setting) }
            },
            onDismiss = { showProxyDialog = false },
        )
    }
}

/** 用哪样凭据登录。 */
internal enum class SignInMode(val label: String) {
    Password("密码"),
    RefreshToken("刷新令牌"),
}

/**
 * 登录页的状态。**这里没有密码字段**：密码由输入框自己拿着，提交时以 CharArray 递进来，
 * 交给 [PikoClientManager] 后由认证模块清零。
 */
internal class SignInState(
    private val clientManager: PikoClientManager,
    private val scope: CoroutineScope,
    initialAccount: String = "",
) {
    var account by mutableStateOf(initialAccount)
    var mode by mutableStateOf(SignInMode.Password)
    var isSigningIn by mutableStateOf(false)
        private set
    // 因为会话失效被送回这里时，一打开就说明原因
    var errorMessage by mutableStateOf(clientManager.takeSignInNotice())
        private set

    /** 正经「继续使用」进入的已保存账号，界面在那一行上转圈。 */
    var usingSaved by mutableStateOf<String?>(null)
        private set

    fun canSubmit(secretLength: Int): Boolean = !isSigningIn && account.isNotBlank() && secretLength > 0

    /** [secret] 的所有权交进来：不管成败，返回前它已被清零。 */
    fun submit(secret: CharArray) {
        if (!canSubmit(secret.size)) {
            secret.fill('\u0000')
            return
        }
        isSigningIn = true
        errorMessage = null
        val name = account.trim()
        val chosen = mode
        scope.launch {
            try {
                val result = when (chosen) {
                    SignInMode.Password -> clientManager.login(name, secret)
                    SignInMode.RefreshToken -> clientManager.loginWithToken(name, secret)
                }
                result.onFailure { errorMessage = messageOf(it, chosen) }
            } finally {
                secret.fill('\u0000')
                isSigningIn = false
            }
        }
    }

    /** 已保存的账号：会话还有效就直接进去；失效了填上账号名，等用户输密码。 */
    fun useSaved(saved: String, onExpired: () -> Unit) {
        if (isSigningIn) return
        isSigningIn = true
        usingSaved = saved
        errorMessage = null
        scope.launch {
            try {
                clientManager.switchTo(saved).onFailure {
                    account = saved
                    errorMessage = "登录已失效，请重新输入密码"
                    onExpired()
                }
            } finally {
                isSigningIn = false
                usingSaved = null
            }
        }
    }

    private fun messageOf(error: Throwable, mode: SignInMode): String = when (error) {
        is dev.pikseek.auth.AuthRejectedException -> error.message ?: "登录被拒绝"
        is dev.pikseek.auth.AuthPolicyViolationException -> error.message ?: "认证请求被拦下"
        is kotlinx.io.IOException -> "连不上 PikPak 认证服务，请检查网络或代理设置"
        is IllegalArgumentException -> error.message ?: "输入不完整"
        else -> if (mode == SignInMode.Password) "登录失败，请检查账号与密码" else "登录失败，请检查刷新令牌"
    }
}

/** 宽窗口左边的品牌区。 */
@Composable
private fun BrandPane(modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = PikoBrandIcons.Logo,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(128.dp).clip(MaterialTheme.shapes.extraLarge),
            )
            Spacer(Modifier.height(28.dp))
            Text("PikSeek", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                "PikPak 桌面客户端 · 独立认证 · 时间轴预览",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SignInForm(
    state: SignInState,
    saved: List<KnownAccount>,
    adding: Boolean,
    showLogo: Boolean,
    proxySummary: String,
    onOpenProxy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var secretVisible by remember { mutableStateOf(false) }
    val secretFocus = remember { FocusRequester() }
    // 密码只存在于这个输入框的状态里，提交时取走并清空
    val secretField = rememberTextFieldState()
    val busy = state.isSigningIn
    val error = state.errorMessage
    val clientManager = LocalPikoServices.current.clientManager
    val storeProblem by produceState<String?>(null) { value = clientManager.auth.status().storeProblem }

    fun submit() {
        val text = secretField.text
        val secret = CharArray(text.length) { text[it] }
        secretField.clearText()
        state.submit(secret)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = FormMaxWidth).fillMaxWidth()) {
            if (showLogo) {
                Icon(
                    imageVector = PikoBrandIcons.Logo,
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.extraLarge),
                )
                Spacer(Modifier.height(24.dp))
            }
            Text(
                text = if (adding) "添加账号" else "登录",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (adding) "当前账号保持登录，可随时切换" else "使用 PikPak 账号",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            if (saved.isNotEmpty()) {
                SectionLabel("继续使用")
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        saved.forEach { account ->
                            SavedAccountRow(
                                saved = account,
                                busy = state.usingSaved == account.account,
                                enabled = !busy,
                                onClick = { state.useSaved(account.account) { secretFocus.requestFocus() } },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                SectionLabel("其他账号")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignInMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = {
                            if (state.mode != mode) {
                                // 换一种凭据：已经输入的那一份不留
                                secretField.clearText()
                                state.mode = mode
                            }
                        },
                        enabled = !busy,
                        label = { Text(mode.label) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.account,
                onValueChange = { state.account = it },
                label = { Text("邮箱、手机号或用户名") },
                leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                singleLine = true,
                enabled = !busy,
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
            val passwordMode = state.mode == SignInMode.Password
            OutlinedSecureTextField(
                state = secretField,
                label = { Text(if (passwordMode) "密码" else "刷新令牌（refresh token）") },
                leadingIcon = { Icon(if (passwordMode) Icons.Outlined.Lock else Icons.Outlined.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { secretVisible = !secretVisible }) {
                        Icon(
                            imageVector = if (secretVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = if (secretVisible) "隐藏" else "显示",
                        )
                    }
                },
                textObfuscationMode = if (secretVisible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                enabled = !busy,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                modifier = Modifier.fillMaxWidth().focusRequester(secretFocus),
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = { submit() },
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = ::submit,
                enabled = state.canSubmit(secretField.text.length),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy && state.usingSaved == null) {
                    InlineLoadingIndicator(color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                    Text("正在登录")
                } else {
                    Text("登录")
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            // 连不上 PikPak 的人往往卡在这一步，代理要在登录之前就能改
            TextButton(onClick = onOpenProxy, enabled = !busy) {
                Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("网络代理：$proxySummary", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            SecurityNote(storeProblem, passwordMode)
        }
    }
}

/** 登录之前就说清楚：凭据发到哪、存不存、怎么存。存不了的时候如实写，不假装。 */
@Composable
private fun SecurityNote(storeProblem: String?, passwordMode: Boolean) {
    val colors = MaterialTheme.colorScheme
    val problem = storeProblem
    Row(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (problem == null) Icons.Outlined.Shield else Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = if (problem == null) colors.onSurfaceVariant else colors.error,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val sent = if (passwordMode) "密码只发往 PikPak 官方认证服务（${AuthNetworkPolicy.allowedHosts.first()}），不保存。" else "刷新令牌只发往 PikPak 官方认证服务，用来换取会话。"
            Text(sent, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (problem == null) {
                Text(
                    "登录会话经 Windows DPAPI 加密后保存在本机，仅当前 Windows 用户可解。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else {
                Text(
                    "$problem。登录仅本次运行有效，退出程序后需重新登录；不会改用明文保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SavedAccountRow(saved: KnownAccount, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(saved.displayName, saved.avatarUrl, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(saved.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val secondary = saved.email.ifBlank { saved.account }
            if (secondary != saved.displayName) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (busy) InlineLoadingIndicator()
    }
}

private val FormMaxWidth = 420.dp
