package staff.android.orders

import android.content.Context
import androidx.room3.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "authority")
data class Authority(
    @PrimaryKey val id: Int = 1,
    val accountId: String?,
    val generation: Long,
    val active: Boolean
)

data class SessionTicket(val accountId: String, val generation: Long)

fun Authority.ticket(): SessionTicket? =
    if (active && accountId != null)
        SessionTicket(accountId, generation)
    else null

@Entity(tableName = "order_intents")
data class OrderIntent(
    @PrimaryKey val operationId: String,
    val accountId: String,
    val admissionWindow: String,
    val productId: String,
    val quantity: Int,
    val phase: String = "Queued",
    val orderId: String? = null,
    val reason: String? = null,
    val attempts: Int = 0,
    val attemptId: String? = null,
    val notBeforeMillis: Long = 0,
    val attention: String? = null,
    val protocolVersion: Int = 1
) {
    fun command() =
        OrderCommand(
            operationId,
            admissionWindow,
            productId,
            quantity,
            protocolVersion)
}

@Dao
interface OrderDao {
    @Query("SELECT * FROM authority WHERE id=1")
    suspend fun authority(): Authority?

    @Upsert suspend fun authority(value: Authority)

    @Query("SELECT * FROM authority WHERE id=1")
    fun observeAuthority(): Flow<Authority?>

    @Upsert suspend fun intent(value: OrderIntent)

    @Query("SELECT * FROM order_intents WHERE operationId=:id")
    suspend fun intent(id: String): OrderIntent?

    @Query(
        """SELECT * FROM order_intents WHERE accountId=:account
        ORDER BY rowid DESC LIMIT 1""")
    suspend fun latest(account: String): OrderIntent?

    @Query(
        """SELECT * FROM order_intents WHERE accountId=:account
        AND phase NOT IN ('Confirmed','Rejected')
        ORDER BY rowid LIMIT 1""")
    suspend fun unresolved(account: String): OrderIntent?

    @Query(
        """SELECT * FROM order_intents WHERE accountId=:account
        ORDER BY rowid DESC LIMIT 1""")
    fun observeLatest(account: String): Flow<OrderIntent?>
}

@Database(
    entities = [Authority::class, OrderIntent::class],
    version = 1,
    exportSchema = true)
abstract class OrderDatabase : RoomDatabase() {
    abstract fun data(): OrderDao
}

fun openOrders(
    context: Context,
    name: String = "orders.db"
): OrderDatabase =
    Room.databaseBuilder<OrderDatabase>(context, name)
        .setDriver(BundledSQLiteDriver())
        .build()
