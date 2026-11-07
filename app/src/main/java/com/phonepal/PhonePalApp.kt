package com.phonepal

import android.app.Application
import com.phonepal.data.AppDatabase
import com.phonepal.data.ItemRepository
import com.phonepal.data.Settings

class PhonePalApp : Application() {
    lateinit var repo: ItemRepository
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        repo = ItemRepository(AppDatabase.get(this).itemDao())
    }
}
