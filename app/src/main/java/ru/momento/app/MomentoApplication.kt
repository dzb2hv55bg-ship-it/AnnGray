package ru.momento.app

import android.app.Application
import com.vk.id.VKID

class MomentoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.VK_CLIENT_CONFIGURED) {
            VKID.init(this)
        }
    }
}
