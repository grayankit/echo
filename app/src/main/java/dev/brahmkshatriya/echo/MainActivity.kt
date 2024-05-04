package dev.brahmkshatriya.echo

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.brahmkshatriya.echo.databinding.ActivityMainBinding
import dev.brahmkshatriya.echo.utils.animateTranslation
import dev.brahmkshatriya.echo.utils.animateVisibility
import dev.brahmkshatriya.echo.utils.checkPermissions
import dev.brahmkshatriya.echo.utils.collect
import dev.brahmkshatriya.echo.utils.emit
import dev.brahmkshatriya.echo.utils.listenFuture
import dev.brahmkshatriya.echo.utils.observe
import dev.brahmkshatriya.echo.viewmodels.ExtensionViewModel
import dev.brahmkshatriya.echo.viewmodels.LoginUserViewModel
import dev.brahmkshatriya.echo.viewmodels.PlayerViewModel
import dev.brahmkshatriya.echo.viewmodels.PlayerViewModel.Companion.connectBrowserToUI
import dev.brahmkshatriya.echo.viewmodels.SnackBar.Companion.configureSnackBar
import dev.brahmkshatriya.echo.viewmodels.UiViewModel
import dev.brahmkshatriya.echo.viewmodels.UiViewModel.Companion.isNightMode
import dev.brahmkshatriya.echo.viewmodels.UiViewModel.Companion.setupPlayerBehavior
import kotlin.math.max
import kotlin.math.min

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    private val extensionViewModel by viewModels<ExtensionViewModel>()
    private val loginViewModel by viewModels<LoginUserViewModel>()
    private val uiViewModel by viewModels<UiViewModel>()
    private val playerViewModel by viewModels<PlayerViewModel>()

    private var controllerFuture: ListenableFuture<MediaBrowser>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        enableEdgeToEdge(
            SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            if (isNightMode()) SystemBarStyle.dark(TRANSPARENT)
            else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
        )

        checkPermissions(this)

        val navView = binding.navView as NavigationBarView
        navView.setOnItemSelectedListener {
            uiViewModel.navigation.value = uiViewModel.navIds.indexOf(it.itemId)
            true
        }
        navView.setOnItemReselectedListener {
            emit(uiViewModel.navigationReselected) { uiViewModel.navIds.indexOf(it.itemId) }
        }
        val isRail = binding.navView is NavigationRailView

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            uiViewModel.setSystemInsets(this, insets)
            insets
        }

        supportFragmentManager.addOnBackStackChangedListener {
            uiViewModel.isMainFragment.value = supportFragmentManager.backStackEntryCount == 0
        }

        binding.navView.post {
            collect(uiViewModel.isMainFragment) { isMainFragment ->
                val insets =
                    uiViewModel.setPlayerNavViewInsets(this, isMainFragment, isRail)
                val visible = uiViewModel.playerSheetState.value != STATE_EXPANDED
                navView.animateTranslation(isRail, isMainFragment, visible) {
                    uiViewModel.setNavInsets(insets)
                }
                binding.navViewOutline?.animateVisibility(isMainFragment && visible)
            }
        }

        observe(uiViewModel.playerSheetState) {
            uiViewModel.setPlayerInsets(this, it != STATE_HIDDEN)
        }

        val collapsedPlayerHeight = resources.getDimension(R.dimen.bottom_player_peek_height)
        observe(uiViewModel.playerSheetOffset) {
            if (it != 0f && isRail)
                navView.translationY = -(1 + min(it, 0f)) * collapsedPlayerHeight
            if (!uiViewModel.isMainFragment.value) return@observe
            val offset = max(0f, it)
            if (isRail) navView.translationX = -navView.width * offset
            else navView.translationY = navView.height * offset
            binding.navViewOutline?.alpha = 1 - offset
        }

        setupPlayerBehavior(uiViewModel, binding.playerFragmentContainer)
        configureSnackBar(binding.navView)

        extensionViewModel.initialize()
        loginViewModel.initialize()

        val sessionToken =
            SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val browserFuture = MediaBrowser.Builder(application, sessionToken).buildAsync()
        listenFuture(browserFuture) {
            val browser = it.getOrElse { e ->
                e.printStackTrace()
                return@listenFuture
            }
            connectBrowserToUI(browser, playerViewModel)
        }

        controllerFuture = browserFuture

    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaBrowser.releaseFuture(it) }
    }


    override fun onNewIntent(intent: Intent?) {
        intent?.hasExtra("fromNotification")?.let {
            uiViewModel.fromNotification.value = it
        }
        super.onNewIntent(intent)
    }
}