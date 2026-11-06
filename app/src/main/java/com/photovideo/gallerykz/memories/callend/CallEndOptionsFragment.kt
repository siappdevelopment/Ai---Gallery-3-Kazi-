package com.photovideo.gallerykz.memories.callend

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.FragmentCallEndOptionsBinding
import com.photovideo.gallerykz.memories.launcher.LauncherNav
import java.util.Calendar

/**
 * One tab of the call summary: a heading and a few actions that really do something with this call.
 * Message sends a quick text to the caller, Reminder sets an alarm to call back,
 * More adds the number to the contacts, opens the call log or the gallery.
 */
class CallEndOptionsFragment : Fragment() {

    private class Option(@StringRes val label: Int, val action: () -> Unit)

    private var _binding: FragmentCallEndOptionsBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentCallEndOptionsBinding.inflate(inflater, container, false)
        _binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = _binding ?: return
        val (title, options) = when (arguments?.getInt(ARG_TAB) ?: 0) {
            TAB_MESSAGE -> R.string.callend_message_title to listOf(R.string.callend_msg_1, R.string.callend_msg_2, R.string.callend_msg_3, R.string.callend_msg_4)
                .map { text -> Option(text) { sendMessage(getString(text)) } }
            TAB_REMINDER -> R.string.callend_reminder_title to listOf(
                Option(R.string.callend_remind_15) { remind(minutes = 15) },
                Option(R.string.callend_remind_1h) { remind(minutes = 60) },
                Option(R.string.callend_remind_tomorrow) { remind(tomorrowMorning = true) },
            )
            else -> R.string.callend_more_title to listOf(
                Option(R.string.callend_more_add_contact) { addContact() },
                Option(R.string.callend_more_call_log) { open(Intent(Intent.ACTION_VIEW).setType(CallLog.Calls.CONTENT_TYPE)) },
                // The app's own gallery: the launcher home scrolled to its gallery page (not the separate stand-alone screen).
                Option(R.string.callend_more_gallery) { open(LauncherNav.galleryIntent(requireContext())) },
            )
        }
        b.tvTitle.setText(title)
        options.forEach { option ->
            val row = layoutInflater.inflate(R.layout.item_call_end_option, b.llOptions, false) as TextView
            row.setText(option.label)
            row.setOnClickListener { option.action() }
            b.llOptions.addView(row)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun number(): String = activity?.intent?.getStringExtra(CallEndActivity.EXTRA_NUMBER).orEmpty()

    private fun sendMessage(text: String) {
        val to = number().filter { it.isDigit() || it == '+' }
        open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).putExtra("sms_body", text))
    }

    /** An alarm to call back: in [minutes], or at 9:00 tomorrow. */
    private fun remind(minutes: Int = 0, tomorrowMorning: Boolean = false) {
        val at = Calendar.getInstance()
        if (tomorrowMorning) {
            at.add(Calendar.DAY_OF_YEAR, 1)
            at.set(Calendar.HOUR_OF_DAY, 9)
            at.set(Calendar.MINUTE, 0)
        } else {
            at.add(Calendar.MINUTE, minutes)
        }
        val label = getString(R.string.callend_reminder_label, number().ifBlank { getString(R.string.callend_private_number) })
        open(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, at.get(Calendar.HOUR_OF_DAY))
                .putExtra(AlarmClock.EXTRA_MINUTES, at.get(Calendar.MINUTE))
                .putExtra(AlarmClock.EXTRA_MESSAGE, label),
        )
    }

    private fun addContact() {
        open(
            Intent(ContactsContract.Intents.Insert.ACTION)
                .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, number()),
        )
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            context?.applicationContext?.let { Toast.makeText(it, R.string.launcher_cannot_open, Toast.LENGTH_SHORT).show() }
        } catch (e: SecurityException) {
            context?.applicationContext?.let { Toast.makeText(it, R.string.launcher_cannot_open, Toast.LENGTH_SHORT).show() }
        }
    }

    companion object {
        private const val ARG_TAB = "tab"
        private const val TAB_MESSAGE = 1
        private const val TAB_REMINDER = 2

        fun newInstance(tab: Int): CallEndOptionsFragment =
            CallEndOptionsFragment().apply { arguments = Bundle().apply { putInt(ARG_TAB, tab) } }
    }
}
