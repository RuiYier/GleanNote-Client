package com.example.gleannote.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.gleannote.core.contract.AppResult
import com.example.gleannote.core.contract.GleanNoteRepository
import com.example.gleannote.core.contract.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/* 启动守卫：读取登录态并决定进入登录页或笔记库；本页不发起网络请求（主文档 6.1）。 */
@Composable
fun SplashScreen(nav: NavHostController, repo: GleanNoteRepository) {
    /*
     * initial 必须为 null：DataStore 首次读取前不得用 false 当结论，
     * 否则已登录用户在冷启动时会被直接送去登录页（本轮实测缺陷）。
     */
    val loggedIn by repo.isLoggedIn.map { it as Boolean? }.collectAsState(initial = null)
    LaunchedEffect(loggedIn) {
        val known = loggedIn ?: return@LaunchedEffect
        nav.navigate(if (known) Routes.LIBRARY else Routes.LOGIN) { popUpTo(Routes.SPLASH) { inclusive = true } }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun LoginScreen(nav: NavHostController, repo: GleanNoteRepository) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("拾页笔记", style = MaterialTheme.typography.headlineMedium)
        Text("拍完即得可编辑 Markdown", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = email, onValueChange = { email = it; error = null },
            label = { Text("邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it; error = null },
            label = { Text("密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (email.isBlank() || password.isBlank()) {
                    error = "请输入邮箱与密码"
                    return@Button
                }
                loading = true
                scope.launch {
                    when (val r = repo.login(email.trim(), password)) {
                        is AppResult.Success -> nav.navigate(Routes.LIBRARY) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                        is AppResult.Failure -> error = r.error.userMessage
                    }
                    loading = false
                }
            },
            enabled = !loading, modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) CircularProgressIndicator(Modifier.height(18.dp)) else Text("登录")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { nav.navigate(Routes.REGISTER) }) { Text("注册账号") }
            TextButton(onClick = { nav.navigate(Routes.RESET_PASSWORD) }) { Text("忘记密码") }
        }
    }
}

@Composable
fun RegisterScreen(nav: NavHostController, repo: GleanNoteRepository) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Text("注册", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("邮箱") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code, onValueChange = { code = it }, label = { Text("验证码（开发环境可留空）") },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(0.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        when (val r = repo.sendCode(email.trim(), "REGISTER")) {
                            is AppResult.Success -> countdown = 60
                            is AppResult.Failure -> error = r.error.userMessage
                        }
                    }
                },
                enabled = countdown == 0 && email.isNotBlank()
            ) { Text(if (countdown > 0) "${countdown}s" else "获取验证码") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("密码（8-32 位，含字母与数字）") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = nickname, onValueChange = { nickname = it }, label = { Text("昵称（可选）") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("我已阅读并同意用户协议与隐私政策", style = MaterialTheme.typography.labelMedium)
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (!agreed) {
                    error = "请先阅读并同意用户协议与隐私政策"
                    return@Button
                }
                loading = true
                scope.launch {
                    when (val r = repo.register(email.trim(), code.trim(), password, nickname.ifBlank { null })) {
                        is AppResult.Success -> nav.navigate(Routes.LIBRARY) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                        is AppResult.Failure -> error = r.error.userMessage
                    }
                    loading = false
                }
            },
            /* 验证码由服务端裁决：开发环境已跳过校验，端上不再拦截空码（主文档 8.3 服务端权威）。 */
            enabled = !loading && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (loading) "注册中…" else "注册并登录") }
        TextButton(onClick = { nav.popBackStack() }) { Text("已有账号，去登录") }
    }
}

@Composable
fun ResetPasswordScreen(nav: NavHostController, repo: GleanNoteRepository) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("重置密码", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("邮箱") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code, onValueChange = { code = it }, label = { Text("验证码（开发环境可留空）") },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    scope.launch {
                        when (val r = repo.sendCode(email.trim(), "RESET_PASSWORD")) {
                            is AppResult.Success -> countdown = 60
                            is AppResult.Failure -> message = r.error.userMessage
                        }
                    }
                },
                enabled = countdown == 0 && email.isNotBlank()
            ) { Text(if (countdown > 0) "${countdown}s" else "获取验证码") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("新密码") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    when (val r = repo.resetPassword(email.trim(), code.trim(), password)) {
                        is AppResult.Success -> {
                            message = "密码已重置，请重新登录"
                            nav.popBackStack()
                        }
                        is AppResult.Failure -> message = r.error.userMessage
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("重置密码") }
        TextButton(onClick = { nav.popBackStack() }) { Text("返回登录") }
    }
}
