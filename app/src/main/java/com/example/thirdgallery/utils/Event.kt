package com.example.thirdgallery.utils

/** One-shot LiveData payload: consumed once so it is not replayed after rotation. */
class Event<out T>(private val content: T) {

    private var handled = false

    fun getContentIfNotHandled(): T? {
        if (handled) return null
        handled = true
        return content
    }
}
