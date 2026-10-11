package staff.android.orders.server

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.serialization.json.Json
import staff.android.orders.*

data class LedgerReply(val code: Int, val record: OrderRecord? = null)

// One fixture process owns this connection. SQLite remains the durable
// commit boundary.
class OrderLedger(path: Path) : AutoCloseable {
    private val connection: Connection =
        DriverManager.getConnection("jdbc:sqlite:$path")
    private val json = Json

    init {
        connection.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA synchronous=FULL")
            s.execute("PRAGMA busy_timeout=5000")
            s.execute(
                "CREATE TABLE IF NOT EXISTS policy (" +
                    "id INTEGER PRIMARY KEY CHECK(id=1), " +
                    "window TEXT NOT NULL)")
            s.execute(
                """CREATE TABLE IF NOT EXISTS orders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account TEXT NOT NULL,
                operation TEXT NOT NULL, product TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                UNIQUE(account, operation))""")
            s.execute(
                """CREATE TABLE IF NOT EXISTS operations (
                account TEXT NOT NULL, operation TEXT NOT NULL,
                payload TEXT NOT NULL,
                outcome TEXT, PRIMARY KEY(account, operation))""")
        }
        connection
            .prepareStatement(
                "INSERT OR IGNORE INTO policy VALUES(1,?)")
            .use {
                it.setString(1, UUID.randomUUID().toString())
                it.executeUpdate()
            }
    }

    @Synchronized
    fun policy(): AdmissionPolicy =
        connection.createStatement().use {
            it.executeQuery("SELECT window FROM policy WHERE id=1")
                .use { row ->
                    check(row.next())
                    AdmissionPolicy(row.getString(1))
                }
        }

    private fun existing(
        account: String,
        id: String
    ): Pair<String, String?>? =
        connection
            .prepareStatement(
                "SELECT payload,outcome FROM operations " +
                    "WHERE account=? AND operation=?")
            .use {
                it.setString(1, account)
                it.setString(2, id)
                it.executeQuery().use { row ->
                    if (row.next()) row.getString(1) to row.getString(2)
                    else null
                }
            }

    @Synchronized
    fun lookup(
        account: String,
        id: String,
        window: String
    ): LedgerReply {
        val row = existing(account, id)
        if (row != null)
            return row.second?.let {
                LedgerReply(200, json.decodeFromString(it))
            } ?: LedgerReply(410)
        return LedgerReply(
            if (window == policy().admissionWindow) 404 else 410)
    }

    @Synchronized
    fun submit(
        account: String,
        command: OrderCommand,
        failBeforeLedger: Boolean = false
    ): LedgerReply {
        connection.autoCommit = false
        try {
            val payload = json.encodeToString(command)
            val previous = existing(account, command.operationId)
            val reply =
                when {
                    previous != null && previous.first != payload ->
                        LedgerReply(409)
                    previous != null ->
                        previous.second?.let {
                            LedgerReply(
                                200,
                                json.decodeFromString<OrderRecord>(it))
                        } ?: LedgerReply(410)
                    command.admissionWindow !=
                        policy().admissionWindow -> LedgerReply(410)
                    else -> {
                        val record =
                            if (command.quantity == 5) {
                                OrderRecord(
                                    command,
                                    "Rejected",
                                    reason =
                                        "Only four packs available")
                            } else {
                                connection
                                    .prepareStatement(
                                        """INSERT INTO orders(
                            account,operation,product,quantity)
                            VALUES(?,?,?,?)""")
                                    .use {
                                        it.setString(1, account)
                                        it.setString(
                                            2, command.operationId)
                                        it.setString(
                                            3, command.productId)
                                        it.setInt(4, command.quantity)
                                        it.executeUpdate()
                                    }
                                val id =
                                    connection.createStatement().use {
                                        it.executeQuery(
                                                "SELECT last_insert_rowid()")
                                            .use { row ->
                                                check(row.next())
                                                row.getLong(1)
                                            }
                                    }
                                OrderRecord(
                                    command,
                                    "Confirmed",
                                    orderId = "order-$id")
                            }
                        if (failBeforeLedger)
                            error(
                                "Injected failure before operation record")
                        connection
                            .prepareStatement(
                                "INSERT INTO operations VALUES(?,?,?,?)")
                            .use {
                                it.setString(1, account)
                                it.setString(2, command.operationId)
                                it.setString(3, payload)
                                it.setString(
                                    4, json.encodeToString(record))
                                it.executeUpdate()
                            }
                        LedgerReply(
                            if (record.status == "Confirmed") 201
                            else 422,
                            record)
                    }
                }
            connection.commit()
            return reply
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    @Synchronized
    fun retireWindow() {
        connection
            .prepareStatement("UPDATE policy SET window=? WHERE id=1")
            .use {
                it.setString(1, UUID.randomUUID().toString())
                it.executeUpdate()
            }
    }

    // Simulates outcome-detail retention expiry. Compact operation
    // tombstones remain.
    @Synchronized
    fun expireOutcomes() {
        connection.createStatement().use {
            it.executeUpdate("UPDATE operations SET outcome=NULL")
        }
    }

    @Synchronized
    fun orderCount(): Int =
        connection.createStatement().use {
            it.executeQuery("SELECT count(*) FROM orders").use { row ->
                check(row.next())
                row.getInt(1)
            }
        }

    @Synchronized
    fun reset() {
        connection.autoCommit = false
        try {
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM operations")
                it.executeUpdate("DELETE FROM orders")
                it.executeUpdate(
                    "DELETE FROM sqlite_sequence WHERE name='orders'")
            }
            retireWindow()
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    @Synchronized
    override fun close() {
        connection.close()
    }
}
