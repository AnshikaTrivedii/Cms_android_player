package com.orion.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for the Orion Digital Signage Player.
 * Annotated with @HiltAndroidApp to trigger Hilt code generation.
 */
@HiltAndroidApp
class OrionPlayerApp : Application()
