package com.carradio.app.data

import com.carradio.app.Constants
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

/** Single shared supabase-kt client (ktor OkHttp engine is picked up from the classpath). */
object SupabaseClientProvider {

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = Constants.SUPABASE_URL,
            supabaseKey = Constants.SUPABASE_ANON_KEY
        ) {
            install(Postgrest)
            install(Storage)
            install(Realtime)
        }
    }
}
