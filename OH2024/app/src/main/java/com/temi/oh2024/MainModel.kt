package com.temi.oh2024

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModelModule {
    @Provides
    @Singleton
    fun provideMainModule() = MainModel()
}

@Singleton
class MainModel {
}