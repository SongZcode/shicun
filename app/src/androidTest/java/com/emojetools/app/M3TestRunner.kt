package com.emojetools.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.emojetools.app.core.*
import com.emojetools.app.core.task.TaskCoordinator
import com.emojetools.app.ui.*
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.File
import java.net.URI

class M3TestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, FixtureApplication::class.java.name, context)
}

class FixtureApplication : TaskApplication() {
    override fun createCoordinator() = object : TaskCoordinator(this@FixtureApplication, "m3-device-tests.db", File(cacheDir, "m3-device-materials")) {
        override suspend fun execute(task: UiTask) {
            if (!task.source.startsWith("fixture://m3/")) { super.execute(task); return }
            ServiceFixture.calls++
            ServiceFixture.entered.complete(Unit)
            if (ServiceFixture.needLogin) throw AppFailure(ErrorCode.NEED_LOGIN, "Fixture session expired")
            ServiceFixture.finish.await()
            val url = js.buildUrl(config.getJSONObject("api").getJSONObject("comment").getString("path"),
                signed.parameters("comment", mapOf("postId" to "123", "cursor" to "0", "deviceId" to "1234567890123456", "count" to config.getInt("count_top").toString())),
                JSONObject().put("ua", config.getString("ua")).put("uifid", "fixture-only").put("now", 1))
            check(URI(url).rawQuery.split('&').count { it.startsWith("a_bogus=") } == 1)
            task.stage = TaskStage.DONE; task.detail = "Fixture complete; no network request"
        }
    }
}

object ServiceFixture {
    var entered = CompletableDeferred<Unit>()
    var finish = CompletableDeferred<Unit>()
    var needLogin = false
    var calls = 0
    fun reset(login: Boolean = false) {
        entered = CompletableDeferred(); finish = CompletableDeferred(); needLogin = login; calls = 0
    }
}
