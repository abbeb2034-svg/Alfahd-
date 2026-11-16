package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object LoginRoute
@Serializable data object HomeRoute
@Serializable data object CitizensRoute
@Serializable data object CitizensImportRoute
@Serializable data class CitizenFormRoute(val personId: String? = null)
@Serializable data class CitizenDetailRoute(val personId: String)
@Serializable data object NeighborhoodsRoute
@Serializable data object AqilsRoute
@Serializable data object RepresentativesRoute
@Serializable data object UsersRoute
@Serializable data object AidProgramsRoute
@Serializable data class AidDetailRoute(val programId: String)
@Serializable data class IdCardRoute(val personId: String)
@Serializable data class CitizenFormExportRoute(val personId: String)
@Serializable data object ReportsRoute
@Serializable data object SearchFilterRoute
@Serializable data object BackupRestoreRoute
@Serializable data object SettingsRoute
@Serializable data object AuditLogsRoute
