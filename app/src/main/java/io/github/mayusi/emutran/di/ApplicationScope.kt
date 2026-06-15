package io.github.mayusi.emutran.di

import javax.inject.Qualifier

/**
 * Qualifies the application-scoped [kotlinx.coroutines.CoroutineScope] provided
 * in [AppModule]. This scope (SupervisorJob + Dispatchers.IO) lives for the
 * whole process and backs eagerly-started StateFlows that must keep collecting
 * for the app's lifetime (e.g. [io.github.mayusi.emutran.data.auth.GithubTokenStore]).
 *
 * Injecting it instead of constructing one inline keeps those classes testable —
 * a test can supply a controlled scope (e.g. a TestScope) rather than a real
 * IO-backed one.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
