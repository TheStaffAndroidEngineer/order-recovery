package staff.android.orders

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

fun sessionTag(ticket: SessionTicket) =
    "session:${ticket.accountId}:${ticket.generation}"

fun ticketData(ticket: SessionTicket) =
    workDataOf(
        "account" to ticket.accountId,
        "generation" to ticket.generation)

fun Data.ticket(): SessionTicket? {
    val account = getString("account") ?: return null
    val generation = getLong("generation", -1)
    return if (account in setOf("A", "B") && generation > 0)
        SessionTicket(account, generation)
    else null
}

class OrderScheduler(private val manager: WorkManager) {
    suspend fun installRecovery() {
        val request =
            PeriodicWorkRequestBuilder<RecoveryWorker>(
                    15, TimeUnit.MINUTES)
                .build()
        manager
            .enqueueUniquePeriodicWork(
                "order-recovery",
                ExistingPeriodicWorkPolicy.KEEP,
                request)
            .await()
    }

    suspend fun enqueue(
        ticket: SessionTicket,
        recovery: Boolean = false
    ) {
        val request =
            OneTimeWorkRequestBuilder<OrderWorker>()
                .setInputData(ticketData(ticket))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(sessionTag(ticket))
                .build()
        manager
            .enqueueUniqueWork(
                "orders:${sessionTag(ticket)}",
                if (recovery) ExistingWorkPolicy.KEEP
                else ExistingWorkPolicy.REPLACE,
                request)
            .await()
    }

    suspend fun cancel(ticket: SessionTicket) {
        manager.cancelAllWorkByTag(sessionTag(ticket)).await()
    }
}

class OrderWorker(
    context: Context,
    params: WorkerParameters,
    private val store: OrderStore
) : CoroutineWorker(context, params) {
    constructor(
        context: Context,
        params: WorkerParameters
    ) : this(
        context,
        params,
        (context.applicationContext as OrderApplication).graph.store)

    override suspend fun doWork(): Result {
        val ticket = inputData.ticket() ?: return Result.failure()
        return when (store.attempt(ticket)) {
            AttemptResult.Finished -> Result.success()
            AttemptResult.Retry -> Result.retry()
        }
    }
}

class RecoveryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as OrderApplication).graph
        val ticket = graph.store.current() ?: return Result.success()
        try {
            if (graph.store.eligible(ticket))
                graph.scheduler.enqueue(ticket, recovery = true)
        } catch (_: StaleSession) {
            /* Sign-out won the race. */
        }
        return Result.success()
    }
}

class AppGraph(context: Context) {
    val database = openOrders(context)
    val store =
        OrderStore(database, HttpOrderApi("http://127.0.0.1:8790"))
    val scheduler = OrderScheduler(WorkManager.getInstance(context))
    val hints =
        RecoveryHints(store) { scheduler.enqueue(it, recovery = true) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val recoveryReady = MutableStateFlow(false)
    val recoveryError = MutableStateFlow(false)

    fun startRecovery() {
        scope.launch {
            try {
                scheduler.installRecovery()
                recoveryReady.value = true
                recoveryError.value = false
                store.current()?.let {
                    if (store.eligible(it))
                        scheduler.enqueue(it, recovery = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recoveryError.value = true
            }
        }
    }
}

class OrderApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setJobSchedulerJobIdRange(20000, 29999)
                .build()

    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph.startRecovery()
        getSystemService(ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        graph.scope.launch {
                            try {
                                graph.hints.reconnected()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                graph.recoveryError.value = true
                            }
                        }
                    }
                })
    }
}
