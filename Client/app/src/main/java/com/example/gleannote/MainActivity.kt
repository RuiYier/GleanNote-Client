package com.example.gleannote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.gleannote.core.contract.GleanNoteRepository
import com.example.gleannote.core.contract.Routes
import com.example.gleannote.core.ui.GleanNoteTheme
import com.example.gleannote.feature.auth.LoginScreen
import com.example.gleannote.feature.auth.RegisterScreen
import com.example.gleannote.feature.auth.ResetPasswordScreen
import com.example.gleannote.feature.auth.SplashScreen
import com.example.gleannote.feature.capture.CaptureScreen
import com.example.gleannote.feature.capture.ConfirmScreen
import com.example.gleannote.feature.conversion.ConversionScreen
import com.example.gleannote.feature.editor.EditorScreen
import com.example.gleannote.feature.editor.OriginalPagesScreen
import com.example.gleannote.feature.library.LibraryScreen
import com.example.gleannote.feature.p1.AppendScreen
import com.example.gleannote.feature.p1.ExportScreen
import com.example.gleannote.feature.p1.ImportScreen
import com.example.gleannote.feature.p1.SearchScreen
import com.example.gleannote.feature.p1.ShareScreen
import com.example.gleannote.feature.p1.TagsScreen
import com.example.gleannote.feature.p1.VersionsScreen
import com.example.gleannote.feature.settings.SettingsScreen
import com.example.gleannote.feature.trash.TrashScreen
import kotlinx.coroutines.flow.map

/*
 * 单 Activity + Navigation Compose；所有界面签名统一为 (nav, repo, noteId)。
 * 深链 gleannote://note/{noteId} 由转换完成通知触发，登录后再导航（Q-12）。
 */
class MainActivity : ComponentActivity() {

    private var pendingNoteId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNoteId = parseNoteId(intent?.data)
        val repo = (application as GleanNoteApp).container.repository
        setContent {
            GleanNoteTheme {
                val nav = rememberNavController()
                GleanNoteNavHost(
                    nav = nav,
                    repo = repo,
                    deepLinkNoteId = pendingNoteId,
                    onDeepLinkConsumed = { pendingNoteId = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNoteId = parseNoteId(intent.data)
    }

    private fun parseNoteId(data: Uri?): String? =
        if (data?.scheme == "gleannote" && data.host == "note") data.lastPathSegment else null
}

@Composable
private fun GleanNoteNavHost(
    nav: NavHostController,
    repo: GleanNoteRepository,
    deepLinkNoteId: String?,
    onDeepLinkConsumed: () -> Unit
) {
    val loggedIn by repo.isLoggedIn.map { it as Boolean? }.collectAsState(initial = null)

    LaunchedEffect(deepLinkNoteId, loggedIn) {
        if (!deepLinkNoteId.isNullOrBlank() && loggedIn == true) {
            nav.navigate(Routes.editor(deepLinkNoteId))
            onDeepLinkConsumed()
        }
    }

    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) { SplashScreen(nav, repo) }
        composable(Routes.LOGIN) { LoginScreen(nav, repo) }
        composable(Routes.REGISTER) { RegisterScreen(nav, repo) }
        composable(Routes.RESET_PASSWORD) { ResetPasswordScreen(nav, repo) }
        composable(Routes.LIBRARY) { LibraryScreen(nav, repo) }
        composable(Routes.CAPTURE) { CaptureScreen(nav, repo) }
        composable(Routes.CONFIRM) { ConfirmScreen(nav, repo) }
        composable(Routes.CONVERSION) { e -> ConversionScreen(nav, repo, e.arguments?.getString("noteId")) }
        composable(Routes.EDITOR) { e -> EditorScreen(nav, repo, e.arguments?.getString("noteId")) }
        composable(Routes.ORIGINAL) { e -> OriginalPagesScreen(nav, repo, e.arguments?.getString("noteId")) }
        composable(Routes.TRASH) { TrashScreen(nav, repo) }
        composable(Routes.SETTINGS) { SettingsScreen(nav, repo) }
        composable(Routes.SEARCH) { SearchScreen(nav, repo) }
        composable(Routes.TAGS) { TagsScreen(nav, repo) }
        composable(Routes.VERSIONS) { e -> VersionsScreen(nav, repo, e.arguments?.getString("noteId")) }
        composable(Routes.SHARE) { e -> ShareScreen(nav, repo, e.arguments?.getString("noteId")) }
        composable(Routes.EXPORT) { e -> ExportScreen(nav, repo, e.arguments?.getString("noteId")) }
        composable(Routes.IMPORT) { ImportScreen(nav, repo) }
        composable(Routes.APPEND) { e -> AppendScreen(nav, repo, e.arguments?.getString("noteId")) }
    }
}
