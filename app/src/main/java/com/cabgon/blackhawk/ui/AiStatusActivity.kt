package com.cabgon.blackhawk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cabgon.blackhawk.R
import kotlinx.coroutines.launch

class AiStatusActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_status)

        val tv = findViewById<TextView>(R.id.tvAiStatus)
        val btnCopy = findViewById<Button>(R.id.btnCopyAiStatus)

        lifecycleScope.launch {
            tv.text = "Cargando estado IA…"
            val report = AiStatusReportBuilder.build(applicationContext)
            tv.text = report

            btnCopy.setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AI Status", report))
                Toast.makeText(this@AiStatusActivity, "Reporte copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
