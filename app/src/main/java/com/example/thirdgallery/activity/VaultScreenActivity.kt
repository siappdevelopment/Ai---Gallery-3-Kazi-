package com.example.thirdgallery.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.thirdgallery.helper.VaultSession

/**
 * Base of every screen that shows vault content. It reports to [VaultSession] when it starts and stops, so the
 * auto-lock rules apply, and it hands over to the PIN screen (closing the other vault screens) when the vault is
 * locked. [onVaultOpen] runs every time the screen comes to the front with the vault open.
 */
abstract class VaultScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VaultSession.register(this)
    }

    override fun onStart() {
        super.onStart()
        if (VaultSession.onScreenStart(this)) onVaultOpen()
    }

    protected open fun onVaultOpen() = Unit

    override fun onStop() {
        super.onStop()
        VaultSession.onScreenStop(isChangingConfigurations)
    }

    override fun onDestroy() {
        VaultSession.unregister(this)
        super.onDestroy()
    }
}
