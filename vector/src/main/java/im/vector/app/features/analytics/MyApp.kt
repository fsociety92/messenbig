/*
 * Copyright (c) 2023 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.analytics

import android.app.Application
import com.google.firebase.FirebaseApp
import com.yandex.metrica.YandexMetrica

import com.yandex.metrica.YandexMetricaConfig


public class MyApp: Application() {
    override fun onCreate() {
        super.onCreate()
        // Creating an extended library configuration.
        val API_key: String = "024d32cf-0acf-4b54-9a90-2f90f11eb45f"
        val config = YandexMetricaConfig.newConfigBuilder(API_key).build()
        // Initializing the AppMetrica SDK.
        YandexMetrica.activate(applicationContext, config)
        // Automatic tracking of user activity.
        YandexMetrica.enableActivityAutoTracking(this)
        // Init FirebaseApp for all processes
        FirebaseApp.initializeApp(this)

        // Then activate AppMetrica SDK
        YandexMetrica.activate(
                this,
                YandexMetricaConfig.newConfigBuilder(API_key)
                        .build()
        )
    }
}
