package com.danielealbano.androidremotecontrolmcp.di

import com.danielealbano.androidremotecontrolmcp.data.repository.ShellSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.ShellSettingsImpl
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuApi
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuApiImpl
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuProviderSwitch
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuProviderSwitchImpl
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuStatusProvider
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuStatusProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.shell.SuShell
import com.danielealbano.androidremotecontrolmcp.services.shell.SuShellBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ShellModule {
    @Binds
    @Singleton
    abstract fun bindShellSettings(impl: ShellSettingsImpl): ShellSettings

    @Binds
    @Singleton
    abstract fun bindShizukuProviderSwitch(impl: ShizukuProviderSwitchImpl): ShizukuProviderSwitch

    @Binds
    @Singleton
    abstract fun bindSuShell(impl: SuShellBackend): SuShell

    @Binds
    @Singleton
    abstract fun bindShizukuApi(impl: ShizukuApiImpl): ShizukuApi

    @Binds
    abstract fun bindShizukuStatusProvider(impl: ShizukuStatusProviderImpl): ShizukuStatusProvider
}
