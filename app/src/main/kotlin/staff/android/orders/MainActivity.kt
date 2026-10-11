package staff.android.orders

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ScreenState(
    val authority: Authority? = null,
    val intent: OrderIntent? = null,
    val loading: Boolean = true,
    val error: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class OrderOwner(private val graph: AppGraph) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    val message = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val state =
        refresh
            .flatMapLatest {
                graph.database
                    .data()
                    .observeAuthority()
                    .flatMapLatest { authority ->
                        val account = authority?.accountId
                        if (account == null)
                            flowOf(
                                ScreenState(authority, loading = false))
                        else
                            graph.database
                                .data()
                                .observeLatest(account)
                                .map {
                                    ScreenState(authority, it, false)
                                }
                    }
                    .catch {
                        emit(ScreenState(loading = false, error = true))
                    }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ScreenState())

    fun refresh() {
        refresh.value += 1
    }

    private fun command(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                action()
                message.value = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message.value =
                    "Action unavailable. Saved operation remains; " +
                        "check status or retry recovery."
            } finally {
                busy.value = false
            }
        }
    }

    fun signIn(account: String) = command {
        val ticket = graph.store.signIn(account)
        if (graph.store.eligible(ticket))
            graph.scheduler.enqueue(ticket, recovery = true)
    }

    fun signOut(ticket: SessionTicket) = command {
        graph.store.signOut(ticket)
        graph.scheduler.cancel(ticket)
    }

    fun submit(ticket: SessionTicket, quantity: Int) = command {
        check(graph.recoveryReady.value)
        graph.store.prepare(ticket, quantity)
        graph.scheduler.enqueue(ticket)
    }

    fun checkStatus(ticket: SessionTicket) = command {
        graph.store.retry(ticket)
        graph.scheduler.enqueue(ticket)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as OrderApplication).graph
        val owner =
            ViewModelProvider(
                this,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(
                        modelClass: Class<T>
                    ): T = OrderOwner(graph) as T
                })[OrderOwner::class.java]
        setContent {
            val state by owner.state.collectAsStateWithLifecycle()
            val message by owner.message.collectAsStateWithLifecycle()
            val busy by owner.busy.collectAsStateWithLifecycle()
            val ready by
                graph.recoveryReady.collectAsStateWithLifecycle()
            val recoveryError by
                graph.recoveryError.collectAsStateWithLifecycle()
            CommonGoodsTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing)
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp)) {
                            CommonGoodsHeader()
                            Text(
                                "Review order",
                                style =
                                    MaterialTheme.typography
                                        .headlineMedium)
                            Text(
                                "Simulated orders only. No payment is taken.")
                            when {
                                state.loading ->
                                    Text("Reading saved operation…")
                                state.error -> {
                                    Text("Saved order unavailable")
                                    Button(onClick = owner::refresh) {
                                        Text("Retry read")
                                    }
                                }
                                else -> {
                                    val authority = state.authority
                                    val ticket = authority?.ticket()
                                    if (ticket == null) {
                                        Text(
                                            if (authority?.accountId !=
                                                null)
                                                "Session expired. Sign in to reconcile " +
                                                    "the saved operation."
                                            else
                                                "Sign in to view your order")
                                        Row(
                                            horizontalArrangement =
                                                Arrangement.spacedBy(
                                                    12.dp)) {
                                                Button(
                                                    onClick = {
                                                        owner.signIn(
                                                            "A")
                                                    },
                                                    enabled = !busy) {
                                                        Text(
                                                            "Sign in A")
                                                    }
                                                Button(
                                                    onClick = {
                                                        owner.signIn(
                                                            "B")
                                                    },
                                                    enabled = !busy) {
                                                        Text(
                                                            "Sign in B")
                                                    }
                                            }
                                    } else {
                                        Text("Account ${ticket.accountId}")
                                        var quantity by
                                            remember(ticket) {
                                                mutableStateOf("2")
                                            }
                                        ProductIdentity("tea", "Tea", "A little everyday ritual")
                                        OutlinedTextField(
                                            value = quantity,
                                            onValueChange = {
                                                quantity = it
                                            },
                                            label = {
                                                Text("Quantity")
                                            },
                                            singleLine = true)
                                        val intent = state.intent
                                        val unresolved =
                                            intent != null &&
                                                intent.phase !in
                                                    setOf(
                                                        "Confirmed",
                                                        "Rejected")
                                        Button(
                                            onClick = {
                                                owner.submit(
                                                    ticket,
                                                    quantity.toInt())
                                            },
                                            enabled =
                                                ready &&
                                                    !busy &&
                                                    !unresolved &&
                                                    quantity
                                                        .toIntOrNull() in
                                                        1..5) {
                                                Text(
                                                    "Submit simulated order")
                                            }
                                        if (intent != null) {
                                            HorizontalDivider()

                                            Text(
                                                "Submitted: Tea × ${intent.quantity}")
                                            ShopMessage("Outcome: ${intent.phase}")
                                            intent.orderId?.let {
                                                ShopMessage("Receipt: $it")
                                            }
                                            intent.reason?.let {
                                                Text(it)
                                            }
                                            intent.attention?.let {
                                                Text(it)
                                            }
                                            if (intent.phase in
                                                setOf(
                                                    "Queued",
                                                    "Unknown"))
                                                Text(
                                                    "The outcome is not confirmed. " +
                                                        "This is not a rejected order.")
                                            OutlinedButton(
                                                onClick = {
                                                    owner.checkStatus(
                                                        ticket)
                                                },
                                                enabled =
                                                    !busy &&
                                                        intent.phase in
                                                            setOf(
                                                                "Queued",
                                                                "Unknown")) {
                                                    Text("Check status")
                                                }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                owner.signOut(ticket)
                                            },
                                            enabled = !busy) {
                                                Text("Sign out")
                                            }
                                        Text(
                                            "Sign-out keeps the saved operation. " +
                                                "It does not cancel an order.")
                                        LabControls {
                                            Text("Account ${ticket.accountId} · generation ${ticket.generation}")
                                            intent?.let { Text("Saved operation: ${it.operationId}") }
                                        }
                                    }
                                }
                            }
                            if (!ready || recoveryError) {
                                Text(
                                    "Recovery admission needs attention")
                                OutlinedButton(
                                    onClick = graph::startRecovery) {
                                        Text("Retry recovery")
                                    }
                            }
                            if (message.isNotBlank()) Text(message)
                        }
                }
            }
        }
    }
}
