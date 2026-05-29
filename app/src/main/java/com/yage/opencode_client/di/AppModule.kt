package com.yage.opencode_client.di

import android.content.Context
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.SettingsManager
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowConfig
import com.yage.voiceflowkit.VoiceFlowMicrophone
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideOpenCodeRepository(): OpenCodeRepository = OpenCodeRepository()

    /**
     * VoiceFlowKit is a DI-agnostic library (no Hilt inside it), so we provide its
     * plain types here. The client's [VoiceFlowConfig.tokenProvider]/endpoint/prompt/
     * terms are refreshed before each session from [SettingsManager]; we seed it with
     * a token provider that always reads the latest sanitized token so a stale config
     * never leaks an old token.
     */
    @Provides
    @Singleton
    fun provideVoiceFlowClient(settingsManager: SettingsManager): VoiceFlowClient {
        val config = VoiceFlowConfig(
            endpoint = settingsManager.aiBuilderBaseURL.trim()
                .ifEmpty { VoiceFlowConfig.DEFAULT_ENDPOINT },
            tokenProvider = {
                com.yage.opencode_client.ui.sanitizeBearerToken(settingsManager.aiBuilderToken)
            },
        )
        return VoiceFlowClient(config)
    }

    /**
     * The microphone needs an Android [Context] for the cache dir + permission check.
     * VoiceFlowKit's mic is cheap and stateless between sessions, so a singleton is fine.
     */
    @Provides
    @Singleton
    fun provideVoiceFlowMicrophone(
        @ApplicationContext context: Context,
    ): VoiceFlowMicrophone = VoiceFlowMicrophone(context)
}
