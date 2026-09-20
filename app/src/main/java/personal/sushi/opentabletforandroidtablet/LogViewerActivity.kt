package personal.sushi.opentabletforandroidtablet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LogViewerActivity : AppCompatActivity() {

    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        findViewById<android.widget.Button>(R.id.btnClear).setOnClickListener {
            LogRepository.clear()
            refreshLog()
        }

        findViewById<android.widget.Button>(R.id.btnCopy).setOnClickListener {
            val text = LogRepository.getAllText()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("log", text))
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }

        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun refreshLog() {
        val lines = LogRepository.getLines()
        val text = if (lines.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            lines.joinToString("\n")
        }
        logText.text = text
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
