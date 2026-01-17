package com.cabgon.blackhawk.content.frequencies

import android.view.View
import android.widget.AdapterView

class SimpleItemSelected(private val onChange: () -> Unit) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onChange()
    override fun onNothingSelected(parent: AdapterView<*>?) = onChange()
}
