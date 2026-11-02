package com.example.thirdgallery.callend

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/** The four tabs of the call summary, in the skill's order: all (media), message, reminder, more. */
class CallEndPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = TAB_COUNT

    override fun createFragment(position: Int): Fragment =
        if (position == 0) CallEndAllFragment() else CallEndOptionsFragment.newInstance(position)

    companion object {
        const val TAB_COUNT = 4
    }
}
