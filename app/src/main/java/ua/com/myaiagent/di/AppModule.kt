package ua.com.myaiagent.di

import androidx.room.Room
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import ua.com.myaiagent.AgentViewModel
import ua.com.myaiagent.BuildConfig
import ua.com.myaiagent.HistoryViewModel
import ua.com.myaiagent.data.ChatRepository
import ua.com.myaiagent.data.OpenAiApi
import ua.com.myaiagent.data.local.AppDatabase
import ua.com.myaiagent.data.local.MIGRATION_1_2

val appModule = module {

    single<HttpClient> {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis  = 60_000
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }

    single<OpenAiApi> {
        OpenAiApi(
            client = get(),
            apiKey = BuildConfig.OPENAI_API_KEY,
        )
    }

    single<AppDatabase> {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "chat_history.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    single { get<AppDatabase>().chatDao() }

    single { ChatRepository(get()) }

    viewModel { AgentViewModel(get(), get()) }

    viewModel { HistoryViewModel(get()) }
}
