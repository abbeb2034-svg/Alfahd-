package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.auth.authStateFlow
import com.example.auth.signOutGoogleAccount
import com.example.data.local.UserRole
import com.example.data.remote.FirestoreCloudRepository
import com.example.ui.components.FahdTopBar
import com.example.ui.navigation.*
import com.example.ui.screens.*
import com.example.ui.viewmodel.CloudSyncViewModel
import com.example.ui.viewmodel.MainViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

private data class TopLevelNavTab(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val testTag: String
)

@Composable
fun AppRoot() {
    val auth = remember { Firebase.auth }
    val currentGoogleUser by auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val gUser = currentGoogleUser
    if (gUser == null) {
        GoogleAuthGateScreen(onAuthSuccess = {})
        return
    }

    val uid = gUser.uid
    val googleEmail = gUser.email
    key(uid) {
        val cloudSyncViewModel: CloudSyncViewModel = viewModel(
            key = uid,
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY]) {
                        "APPLICATION_KEY missing from CreationExtras"
                    }
                    val databaseId = app.getString(R.string.firestore_database_id)
                    val firestore = FirebaseFirestore.getInstance(databaseId)
                    CloudSyncViewModel(
                        cloudRepo = FirestoreCloudRepository(firestore, auth),
                        currentUserId = uid
                    )
                }
            }
        )
        val mainViewModel: MainViewModel = viewModel(key = "local_$uid")
        AuthenticatedAppContent(
            viewModel = mainViewModel,
            cloudSyncViewModel = cloudSyncViewModel,
            googleEmail = googleEmail,
            googleDisplayName = gUser.displayName,
            isGoogleEmailVerified = gUser.isEmailVerified
        )
    }
}

@Composable
private fun AuthenticatedAppContent(
    viewModel: MainViewModel,
    cloudSyncViewModel: CloudSyncViewModel,
    googleEmail: String?,
    googleDisplayName: String?,
    isGoogleEmailVerified: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val permissions by viewModel.currentPermissions.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val loginError by viewModel.loginError.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val isVerifiedBootstrapAdmin = isGoogleEmailVerified &&
        !googleEmail.isNullOrBlank() &&
        googleEmail.equals(FirestoreCloudRepository.BOOTSTRAP_ADMIN_EMAIL, ignoreCase = true)
    var hasTriedAutoAdminLogin by remember(googleEmail) { mutableStateOf(false) }

    LaunchedEffect(isVerifiedBootstrapAdmin, currentUser) {
        if (isVerifiedBootstrapAdmin && currentUser == null && !hasTriedAutoAdminLogin) {
            hasTriedAutoAdminLogin = true
            viewModel.loginAsVerifiedCloudAdmin(googleEmail ?: "", googleDisplayName ?: "")
        }
    }

    val user = currentUser
    if (user == null) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { _ ->
            LoginScreen(
                isBusy = isBusy,
                errorMessage = loginError,
                verifiedCloudAdminEmail = if (isVerifiedBootstrapAdmin) googleEmail else null,
                onQuickCloudAdminLogin = if (isVerifiedBootstrapAdmin) {
                    { viewModel.loginAsVerifiedCloudAdmin(googleEmail ?: "", googleDisplayName ?: "") }
                } else null,
                onLogin = { u, p -> viewModel.login(u, p) },
                onClearError = { viewModel.clearLoginError() }
            )
        }
        return
    }

    val performCloudSync = {
        cloudSyncViewModel.syncNow(viewModel.localDb) { msg ->
            viewModel.showMessage(msg)
        }
    }

    val performGoogleSignOut = {
        viewModel.logout()
        signOutGoogleAccount(credentialManager, scope)
    }

    val isAdmin = UserRole.fromDb(user.role) == UserRole.ADMIN
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    val topTabs = remember {
        listOf(
            TopLevelNavTab("الرئيسية", Icons.Filled.Home, HomeRoute, "nav_home"),
            TopLevelNavTab("المواطنون", Icons.Filled.People, CitizensRoute, "nav_citizens"),
            TopLevelNavTab("المساعدات", Icons.Filled.VolunteerActivism, AidProgramsRoute, "nav_aid"),
            TopLevelNavTab("التقارير", Icons.Filled.Assessment, ReportsRoute, "nav_reports")
        )
    }

    val currentTitle = when {
        currentDest?.hasRoute<HomeRoute>() == true -> "سجل الفهد"
        currentDest?.hasRoute<CitizensRoute>() == true -> "إدارة المواطنين"
        currentDest?.hasRoute<CitizensImportRoute>() == true -> "استيراد المواطنين من إكسل"
        currentDest?.hasRoute<CitizenFormRoute>() == true -> "نموذج بيانات المواطن"
        currentDest?.hasRoute<CitizenDetailRoute>() == true -> "ملف المواطن"
        currentDest?.hasRoute<NeighborhoodsRoute>() == true -> "الحارات والمربعات السكنية"
        currentDest?.hasRoute<AqilsRoute>() == true -> "إدارة عقال الحارات"
        currentDest?.hasRoute<RepresentativesRoute>() == true -> "إدارة مندوبي المربعات"
        currentDest?.hasRoute<UsersRoute>() == true -> "المستخدمون والصلاحيات"
        currentDest?.hasRoute<AidProgramsRoute>() == true -> "المساعدات والإعانات"
        currentDest?.hasRoute<AidDetailRoute>() == true -> "المستفيدون والتوزيع"
        currentDest?.hasRoute<IdCardRoute>() == true -> "البطاقة التعريفية"
        currentDest?.hasRoute<CitizenFormExportRoute>() == true -> "استمارة المواطن الرسمية"
        currentDest?.hasRoute<ReportsRoute>() == true -> "الكشوفات والتقارير"
        currentDest?.hasRoute<SearchFilterRoute>() == true -> "البحث والتصفية"
        currentDest?.hasRoute<BackupRestoreRoute>() == true -> "النسخ الاحتياطي والاستعادة"
        currentDest?.hasRoute<SettingsRoute>() == true -> "إعدادات التطبيق"
        currentDest?.hasRoute<AuditLogsRoute>() == true -> "سجل العمليات"
        else -> "سجل الفهد"
    }

    val isTopLevel = currentDest?.hasRoute<HomeRoute>() == true ||
        currentDest?.hasRoute<CitizensRoute>() == true ||
        currentDest?.hasRoute<AidProgramsRoute>() == true ||
        currentDest?.hasRoute<ReportsRoute>() == true

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 680.dp

        Scaffold(
            topBar = {
                FahdTopBar(
                    title = currentTitle,
                    subtitle = "${user.fullName} (${UserRole.fromDb(user.role).arabicLabel})",
                    canNavigateBack = !isTopLevel,
                    onBack = { navController.popBackStack() },
                    pendingSyncCount = metrics.pendingSyncCount,
                    onSyncClick = performCloudSync
                )
            },
            bottomBar = {
                if (!useRail && isTopLevel) {
                    NavigationBar {
                        topTabs.forEach { tab ->
                            val selected = currentDest?.hasRoute(tab.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(HomeRoute) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail {
                        topTabs.forEach { tab ->
                            val selected = currentDest?.hasRoute(tab.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(HomeRoute) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    NavHost(
                        navController = navController,
                        startDestination = HomeRoute
                    ) {
                        composable<HomeRoute> {
                            val settings by viewModel.settings.collectAsStateWithLifecycle()
                            HomeScreen(
                                user = user,
                                permissions = permissions,
                                settings = settings,
                                metrics = metrics,
                                googleEmail = googleEmail,
                                onNavigateCitizens = { navController.navigate(CitizensRoute) },
                                onAddCitizen = { navController.navigate(CitizenFormRoute(null)) },
                                onImportExcel = { navController.navigate(CitizensImportRoute) },
                                onNavigateSearch = { navController.navigate(SearchFilterRoute) },
                                onNavigateNeighborhoods = { navController.navigate(NeighborhoodsRoute) },
                                onNavigateAqils = { navController.navigate(AqilsRoute) },
                                onNavigateReps = { navController.navigate(RepresentativesRoute) },
                                onNavigateUsers = { navController.navigate(UsersRoute) },
                                onNavigateAid = { navController.navigate(AidProgramsRoute) },
                                onNavigateReports = { navController.navigate(ReportsRoute) },
                                onNavigateBackup = { navController.navigate(BackupRestoreRoute) },
                                onNavigateSettings = { navController.navigate(SettingsRoute) },
                                onNavigateAuditLogs = { navController.navigate(AuditLogsRoute) },
                                onSyncNow = performCloudSync,
                                onLogout = { viewModel.logout() },
                                onGoogleSignOut = performGoogleSignOut
                            )
                        }
                        composable<CitizensRoute> {
                            CitizensListScreen(
                                viewModel = viewModel,
                                canAdd = isAdmin || permissions.canAddCitizen,
                                onAddCitizen = { navController.navigate(CitizenFormRoute(null)) },
                                onImportExcel = { navController.navigate(CitizensImportRoute) },
                                onSelectCitizen = { id -> navController.navigate(CitizenDetailRoute(id)) }
                            )
                        }
                        composable<CitizensImportRoute> {
                            CitizensImportScreen(
                                viewModel = viewModel,
                                onTriggerCloudSync = performCloudSync,
                                onDoneNavigateCitizens = {
                                    navController.navigate(CitizensRoute) {
                                        popUpTo(HomeRoute) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }
                        composable<CitizenFormRoute> { entry ->
                            val args = entry.toRoute<CitizenFormRoute>()
                            CitizenFormScreen(
                                existingPersonId = args.personId,
                                viewModel = viewModel,
                                onSaved = { savedId ->
                                    navController.navigate(CitizenDetailRoute(savedId)) {
                                        popUpTo(CitizensRoute) { inclusive = false }
                                    }
                                }
                            )
                        }
                        composable<CitizenDetailRoute> { entry ->
                            val args = entry.toRoute<CitizenDetailRoute>()
                            CitizenDetailScreen(
                                personId = args.personId,
                                viewModel = viewModel,
                                isAdmin = isAdmin,
                                canEdit = isAdmin || permissions.canEditCitizen,
                                canDelete = isAdmin || permissions.canDeleteCitizen,
                                onEdit = { id -> navController.navigate(CitizenFormRoute(id)) },
                                onOpenIdCard = { id -> navController.navigate(IdCardRoute(id)) },
                                onOpenOfficialForm = { id -> navController.navigate(CitizenFormExportRoute(id)) },
                                onDeleted = { navController.popBackStack() }
                            )
                        }
                        composable<NeighborhoodsRoute> {
                            NeighborhoodsScreen(
                                viewModel = viewModel,
                                canManage = isAdmin || permissions.canManageAreas
                            )
                        }
                        composable<AqilsRoute> {
                            AqilsScreen(viewModel = viewModel)
                        }
                        composable<RepresentativesRoute> {
                            RepresentativesScreen(viewModel = viewModel)
                        }
                        composable<UsersRoute> {
                            UsersScreen(
                                viewModel = viewModel,
                                onProfileSaved = { savedProfile ->
                                    cloudSyncViewModel.syncSingleProfile(savedProfile)
                                }
                            )
                        }
                        composable<AidProgramsRoute> {
                            AidProgramsScreen(
                                viewModel = viewModel,
                                canManageAid = isAdmin || permissions.canManageAid,
                                onOpenProgram = { progId -> navController.navigate(AidDetailRoute(progId)) }
                            )
                        }
                        composable<AidDetailRoute> { entry ->
                            val args = entry.toRoute<AidDetailRoute>()
                            AidDetailScreen(
                                programId = args.programId,
                                viewModel = viewModel,
                                canManageAid = isAdmin || permissions.canManageAid
                            )
                        }
                        composable<IdCardRoute> { entry ->
                            val args = entry.toRoute<IdCardRoute>()
                            IdCardScreen(personId = args.personId, viewModel = viewModel)
                        }
                        composable<CitizenFormExportRoute> { entry ->
                            val args = entry.toRoute<CitizenFormExportRoute>()
                            CitizenFormExportScreen(personId = args.personId, viewModel = viewModel)
                        }
                        composable<ReportsRoute> {
                            ReportsScreen(
                                viewModel = viewModel,
                                canExport = isAdmin || permissions.canExport
                            )
                        }
                        composable<SearchFilterRoute> {
                            SearchFilterScreen(
                                viewModel = viewModel,
                                onSelectCitizen = { id -> navController.navigate(CitizenDetailRoute(id)) }
                            )
                        }
                        composable<BackupRestoreRoute> {
                            BackupRestoreScreen(viewModel = viewModel)
                        }
                        composable<SettingsRoute> {
                            SettingsScreen(
                                viewModel = viewModel,
                                isAdmin = isAdmin,
                                onCloudSync = performCloudSync
                            )
                        }
                        composable<AuditLogsRoute> {
                            AuditLogsScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}
