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
import ua.com.myaiagent.Day11ViewModel
import ua.com.myaiagent.Week3ViewModel
import ua.com.myaiagent.data.invariants.InvariantStore
import ua.com.myaiagent.HistoryViewModel
import ua.com.myaiagent.data.ChatRepository
import ua.com.myaiagent.data.ContextCompressor
import ua.com.myaiagent.data.OpenAiApi
import ua.com.myaiagent.data.context.BranchingStrategy
import ua.com.myaiagent.data.context.ContextStrategy
import ua.com.myaiagent.data.context.SlidingWindowStrategy
import ua.com.myaiagent.data.context.StickyFactsStrategy
import ua.com.myaiagent.data.context.StrategyType
import ua.com.myaiagent.data.context.SummaryStrategy
import ua.com.myaiagent.data.local.AppDatabase
import ua.com.myaiagent.data.memory.UserProfileStore
import ua.com.myaiagent.data.local.MIGRATION_1_2
import ua.com.myaiagent.data.local.MIGRATION_2_3
import ua.com.myaiagent.data.local.MIGRATION_3_4
import ua.com.myaiagent.data.local.MIGRATION_4_5

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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }

    single { get<AppDatabase>().chatDao() }

    single { ChatRepository(get()) }

    single { ContextCompressor(get()) }

    // ── Context Strategies ───────────────────────────────────────────────────

    single<SlidingWindowStrategy> { SlidingWindowStrategy() }

    single<SummaryStrategy> { SummaryStrategy(get(), get()) }

    single<StickyFactsStrategy> { StickyFactsStrategy(get(), get()) }

    single<BranchingStrategy> { BranchingStrategy(get()) }

    single<Map<StrategyType, ContextStrategy>> {
        mapOf(
            StrategyType.SLIDING_WINDOW to get<SlidingWindowStrategy>(),
            StrategyType.SUMMARY to get<SummaryStrategy>(),
            StrategyType.STICKY_FACTS to get<StickyFactsStrategy>(),
            StrategyType.BRANCHING to get<BranchingStrategy>(),
        )
    }

    // ── ViewModels ───────────────────────────────────────────────────────────

    single { UserProfileStore(androidContext()) }

    single { InvariantStore(androidContext()) }

    viewModel { AgentViewModel(get(), get(), get()) }

    viewModel { HistoryViewModel(get()) }

    viewModel { Day11ViewModel(get(), androidContext(), get()) }

    viewModel { Week3ViewModel(get(), androidContext(), get(), get()) }

}
