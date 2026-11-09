package com.macam

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*

/**
 * Version Pro (achat unique via Google Play) : Atelier, authenticité, calibration du capteur, style personnel.
 * Les versions de test (debug) sont toujours Pro.
 */
object Pro {
    const val PRODUCT_ID = "macam_pro"
    private var client: BillingClient? = null
    @Volatile private var owned = false
    private var details: ProductDetails? = null

    val isPro get() = BuildConfig.DEBUG || owned
    /** Prix localisé, connu une fois la boutique interrogée. */
    val price get() = details?.oneTimePurchaseOfferDetails?.formattedPrice

    fun init(ctx: Context) {
        owned = ctx.getSharedPreferences("macam", Context.MODE_PRIVATE).getBoolean("pro", false)
        if (client != null) return
        val c = BillingClient.newBuilder(ctx.applicationContext)
            .setListener { result, purchases -> if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(ctx, purchases.orEmpty()) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode != BillingClient.BillingResponseCode.OK) return
                c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { _, list -> handle(ctx, list) }
                c.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(
                    QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
                )).build()) { _, list -> details = list.firstOrNull() }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun handle(ctx: Context, purchases: List<Purchase>) {
        val p = purchases.firstOrNull { PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED } ?: return
        owned = true
        ctx.getSharedPreferences("macam", Context.MODE_PRIVATE).edit().putBoolean("pro", true).apply()
        if (!p.isAcknowledged) client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {}
    }

    /** Lance l'achat ; renvoie false si la boutique n'est pas disponible (application hors Play Store). */
    fun buy(activity: Activity): Boolean {
        val d = details ?: return false
        client?.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()
        )).build()) ?: return false
        return true
    }

    /** À appeler avant une fonction Pro : exécute [block] si Pro, sinon propose l'achat. */
    fun gate(activity: Activity, feature: String, block: () -> Unit) {
        if (isPro) return block()
        android.app.AlertDialog.Builder(activity)
            .setTitle("$feature · Ma Cam Pro")
            .setMessage("Achat unique${price?.let { " ($it)" } ?: ""}, sans abonnement : Atelier vidéo, authenticité des photos, calibration du capteur et style personnel.")
            .setPositiveButton("Débloquer") { _, _ ->
                if (!buy(activity)) android.widget.Toast.makeText(activity, "Boutique indisponible : installez Ma Cam depuis Google Play", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Plus tard", null).show()
    }
}
