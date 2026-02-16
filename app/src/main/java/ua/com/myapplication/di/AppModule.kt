package ua.com.myapplication.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import ua.com.myapplication.AgentViewModel
import ua.com.myapplication.BuildConfig
import ua.com.myapplication.data.OpenAiApi

val appModule = module {

    single<HttpClient> {
        HttpClient(OkHttp) {
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

    viewModel { AgentViewModel(get()) }
}
