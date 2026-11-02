package com.example.thirdgallery.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ActivityLauncherWidgetsBinding
import com.example.thirdgallery.databinding.ItemLauncherWidgetBinding

/** The widget picker: one card per widget with an Add button that pins it to the home screen. */
class LauncherWidgetsActivity : AppCompatActivity() {

    private var binding: ActivityLauncherWidgetsBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityLauncherWidgetsBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { finish() }

        val inflater = LayoutInflater.from(this)
        LauncherWidgetType.entries.forEach { type ->
            val item = ItemLauncherWidgetBinding.inflate(inflater, b.list, false)
            item.ivPreview.setImageResource(type.preview)
            item.tvTitle.setText(type.title)
            item.tvDescription.setText(type.description)
            item.btnAdd.setOnClickListener { pin(type) }
            b.list.addView(item.root)
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    private fun pin(type: LauncherWidgetType) {
        val message = when (LauncherWidgetPinner.request(this, type)) {
            LauncherWidgetPinner.Result.REQUESTED -> return
            LauncherWidgetPinner.Result.NOT_SUPPORTED -> R.string.launcher_widget_pin_not_supported
            LauncherWidgetPinner.Result.FAILED -> R.string.launcher_widget_pin_failed
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, LauncherWidgetsActivity::class.java)
    }
}
