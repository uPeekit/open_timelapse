package org.peekit.opentimelapse.pro

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.peekit.opentimelapse.data.LogRepository

fun createProAccess(context: Context, log: LogRepository): ProAccess = PlayProAccess(context, log)

/**
 * Pro as a single one-time purchase through Google Play Billing.
 *
 * The last known answer is kept in preferences and trusted until the store says otherwise:
 * a phone on a windowsill with no network must not lose the features it paid for. Only a
 * successful query that finds no purchase - a refund - locks them again.
 */
private class PlayProAccess(context: Context, private val log: LogRepository) :
    ProAccess, PurchasesUpdatedListener {

    private val prefs = context.getSharedPreferences("pro", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(ProState(unlocked = prefs.getBoolean(KEY_UNLOCKED, false)))
    override val state: StateFlow<ProState> = _state

    @Volatile
    private var details: ProductDetails? = null

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override fun refresh() {
        when (client.connectionState) {
            BillingClient.ConnectionState.CONNECTED -> query()
            BillingClient.ConnectionState.CONNECTING -> Unit
            else -> client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingResponseCode.OK) query()
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    override fun purchase(activity: Activity) {
        val product = details
        if (product == null) {
            // No Play Store, no network, or the product is not published yet.
            log.message("Pro cannot be bought right now: Google Play did not answer")
            refresh()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build()
                )
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            // This list is only what was just bought, so it can unlock but never lock.
            BillingResponseCode.OK -> if (owned(purchases.orEmpty()).isNotEmpty()) apply(purchases.orEmpty())
            BillingResponseCode.ITEM_ALREADY_OWNED -> query()
            else -> Unit
        }
    }

    private fun query() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        ) { result, purchases ->
            if (result.responseCode == BillingResponseCode.OK) apply(purchases)
        }

        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(ProductType.INAPP)
            .build()
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        ) { result, found ->
            if (result.responseCode != BillingResponseCode.OK) return@queryProductDetailsAsync
            details = found.productDetailsList.firstOrNull()
            val price = details?.oneTimePurchaseOfferDetails?.formattedPrice
            _state.update { it.copy(price = price) }
        }
    }

    /** A pending purchase - cash at a shop, say - is not owned until it completes. */
    private fun owned(purchases: List<Purchase>) = purchases.filter {
        PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED
    }

    private fun apply(purchases: List<Purchase>) {
        val owned = owned(purchases)
        // Unacknowledged purchases are refunded by Play after three days.
        owned.filterNot { it.isAcknowledged }.forEach { purchase ->
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            ) { }
        }
        val unlocked = owned.isNotEmpty()
        prefs.edit().putBoolean(KEY_UNLOCKED, unlocked).apply()
        _state.update { it.copy(unlocked = unlocked) }
    }

    private companion object {
        /** The one-time product's id in Play Console. Never change it once it is on sale. */
        const val PRODUCT_ID = "pro_unlock"
        const val KEY_UNLOCKED = "unlocked"
    }
}
