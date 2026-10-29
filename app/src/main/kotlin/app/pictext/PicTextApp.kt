package app.pictext

import android.app.Application

class PicTextApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
