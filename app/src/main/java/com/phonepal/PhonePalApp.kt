package com.phonepal

import android.app.Application
import com.phonepal.data.AppDatabase
import com.phonepal.data.ItemRepository
import com.phonepal.data.RagDao
import com.phonepal.data.Settings
import com.phonepal.work.InstagramBackfill
import com.phonepal.work.RagSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhonePalApp : Application() {
    lateinit var repo: ItemRepository
        private set
    lateinit var settings: Settings
        private set
    lateinit var ragDao: RagDao
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        val db = AppDatabase.get(this)
        repo = ItemRepository(db.itemDao())
        ragDao = db.ragDao()
        // Index anything new/changed for "Ask" (the first time: the whole existing library).
        RagSyncWorker.enqueue(this, delaySec = 10)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { InstagramBackfill.runOnce(this@PhonePalApp) }
                .onFailure { android.util.Log.w("PhonePal", "Instagram backfill failed", it) }
        }
    }
}
