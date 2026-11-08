package ru.dimlife.finance.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData

private const val BANNER_AD_UNIT_ID = "R-M-20364460-2"

@Composable
fun YandexBannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val widthDp = (context.resources.displayMetrics.widthPixels / density).toInt()

    AndroidView(
        modifier = modifier.fillMaxWidth().height(50.dp),
        factory = { viewContext ->
            BannerAdView(viewContext).apply {
                setAdSize(BannerAdSize.fixedSize(widthDp))
                setBannerAdEventListener(object : BannerAdEventListener {
                    override fun onAdLoaded() = Unit
                    override fun onAdFailedToLoad(adRequestError: AdRequestError) = Unit
                    override fun onAdClicked() = Unit
                    override fun onImpression(impressionData: ImpressionData?) = Unit
                })
                loadAd(AdRequest.Builder(BANNER_AD_UNIT_ID).build())
            }
        },
        onRelease = { banner -> banner.destroy() }
    )
}
