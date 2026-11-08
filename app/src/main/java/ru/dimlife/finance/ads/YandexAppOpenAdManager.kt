package ru.dimlife.finance.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.yandex.mobile.ads.appopenad.AppOpenAd
import com.yandex.mobile.ads.appopenad.AppOpenAdEventListener
import com.yandex.mobile.ads.appopenad.AppOpenAdLoadListener
import com.yandex.mobile.ads.appopenad.AppOpenAdLoader
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds

/**
 * App-open ads are deliberately skipped on the first launch.
 * A loaded ad may be shown only after the app stayed in background for at least two minutes.
 */
class YandexAppOpenAdManager(
    private val application: Application
) : Application.ActivityLifecycleCallbacks {

    companion object {
        private const val AD_UNIT_ID = "R-M-20364460-1"
        private const val MIN_BACKGROUND_TIME_MS = 2 * 60 * 1000L
    }

    private val loader = AppOpenAdLoader(application)
    private var appOpenAd: AppOpenAd? = null
    private var startedActivities = 0
    private var wasInBackground = false
    private var backgroundStartedAt = 0L
    private var isLoading = false
    private var isShowing = false
    private var currentActivity: Activity? = null

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        YandexAds.initialize(application) {
            loadAd()
        }
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        clearAd()
        currentActivity = null
    }

    private fun loadAd() {
        if (isLoading || appOpenAd != null) return
        isLoading = true
        loader.loadAd(
            AdRequest.Builder(AD_UNIT_ID).build(),
            object : AppOpenAdLoadListener {
                override fun onAdLoaded(ad: AppOpenAd) {
                    isLoading = false
                    appOpenAd = ad
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    isLoading = false
                }
            }
        )
    }

    private fun showAdIfEligible(activity: Activity) {
        if (!wasInBackground || isShowing) return
        val backgroundDuration = System.currentTimeMillis() - backgroundStartedAt
        wasInBackground = false
        if (backgroundDuration < MIN_BACKGROUND_TIME_MS) return

        val ad = appOpenAd ?: return
        isShowing = true
        ad.setAdEventListener(object : AppOpenAdEventListener {
            override fun onAdShown() = Unit

            override fun onAdFailedToShow(error: AdError) {
                isShowing = false
                clearAd()
                loadAd()
            }

            override fun onAdDismissed() {
                isShowing = false
                clearAd()
                loadAd()
            }

            override fun onAdClicked() = Unit

            override fun onAdImpression(impressionData: ImpressionData?) = Unit
        })
        ad.show(activity)
    }

    private fun clearAd() {
        appOpenAd?.setAdEventListener(null)
        appOpenAd = null
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
        if (startedActivities == 0) {
            if (wasInBackground) showAdIfEligible(activity)
        }
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            wasInBackground = true
            backgroundStartedAt = System.currentTimeMillis()
        }
        if (currentActivity === activity) currentActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) { currentActivity = activity }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }
}
