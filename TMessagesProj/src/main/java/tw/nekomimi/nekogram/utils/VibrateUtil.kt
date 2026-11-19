package tw.nekomimi.nekogram.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import org.telegram.messenger.ApplicationLoader
import tw.nekomimi.nekogram.NekoConfig
import xyz.nextalone.nagram.hgram.HgramHaptics
import xyz.nextalone.nagram.hgram.HgramHapticProfile

object VibrateUtil {

    lateinit var vibrator: Vibrator

    private fun initVibrator() {
        if (!::vibrator.isInitialized) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun vibrate(time: Long = 200L, effect: VibrationEffect? = null) {
        vibrate(HgramHapticProfile.PLACE_LIST_ITEMS, time, effect)
    }

    @JvmStatic
    @JvmOverloads
    fun vibrate(place: String, time: Long = 200L, effect: VibrationEffect? = null) {
        if (NekoConfig.disableVibration.Bool()) return
        HgramHaptics.play(place)
    }

    @JvmStatic
    fun disableHapticFeedback(view: View?) {
        view?.isHapticFeedbackEnabled = false
        (view as? ViewGroup)?.children?.forEach(::disableHapticFeedback)
    }

    @JvmStatic
    fun vibrate(longs: LongArray, repeat: Int) {
        if (NekoConfig.disableVibration.Bool()) return
        HgramHaptics.play(HgramHapticProfile.PLACE_MINIAPPS)
    }
}
