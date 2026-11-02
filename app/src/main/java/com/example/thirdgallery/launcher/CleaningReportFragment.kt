package com.example.thirdgallery.launcher

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.FragmentCleaningReportBinding
import com.example.thirdgallery.helper.CleaningReportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * Cleaning report: what the last cleanup removed, plus the device storage as it is now. It is the right page of the launcher
 * pager: a left swipe on Home reveals it. Nothing cleaned yet shows the "Already clean" state. Done and Back return to Home.
 */
class CleaningReportFragment : Fragment() {

    private var _binding: FragmentCleaningReportBinding? = null
    private val numbers: NumberFormat = NumberFormat.getIntegerInstance()
    private val host get() = activity as? LauncherHost

    // A cleanup finishing while this page is alive (the pager keeps it started) updates it at once.
    private val storeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderResult() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentCleaningReportBinding.inflate(inflater, container, false)
        _binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = _binding ?: return
        ViewCompat.setOnApplyWindowInsetsListener(b.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { host?.showHome() }
        b.doneButton.setOnClickListener { host?.showHome() }
        renderResult()
    }

    override fun onStart() {
        super.onStart()
        CleaningReportStore.register(requireContext(), storeListener)
        renderResult()
    }

    override fun onResume() {
        super.onResume()
        // Resumed means the page is on screen: read the storage as it is right now.
        loadStorage()
    }

    override fun onStop() {
        context?.let { CleaningReportStore.unregister(it, storeListener) }
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun renderResult() {
        val b = _binding ?: return
        val context = context ?: return
        val report = CleaningReportStore.load(context)
        val clean = report == null
        b.resultTitle.setText(if (clean) R.string.report_already_clean else R.string.report_complete)
        b.resultMessage.setText(if (clean) R.string.report_already_clean_message else R.string.report_complete_message)
        b.recoveredValue.text = Formatter.formatFileSize(context, report?.bytes ?: 0L)
        b.itemsValue.text = numbers.format(report?.items ?: 0)
    }

    private fun loadStorage() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (used, total) = withContext(Dispatchers.IO) { storage() }
            renderStorage(used, total)
        }
    }

    private fun renderStorage(used: Long, total: Long) {
        val b = _binding ?: return
        val context = context ?: return
        if (total <= 0L) {
            b.storageCard.visibility = View.GONE
            return
        }
        val share = (used.toDouble() / total).coerceIn(0.0, 1.0)
        b.storagePercent.text = getString(R.string.report_percent, (share * 100).toInt())
        b.storageBar.progress = (share * b.storageBar.max).toInt()
        b.storageDetail.text = getString(R.string.report_storage_detail, Formatter.formatFileSize(context, used), Formatter.formatFileSize(context, total))
        b.storageCard.visibility = View.VISIBLE
    }

    /** Used and total bytes of the device storage; 0 / 0 if it cannot be read. */
    @Suppress("DEPRECATION")
    private fun storage(): Pair<Long, Long> = try {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val total = stat.totalBytes
        (total - stat.availableBytes) to total
    } catch (e: Exception) {
        0L to 0L
    }
}
