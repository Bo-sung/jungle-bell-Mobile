package com.junglebell.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.junglebell.mobile.widget.LaundrySourceStore
import com.junglebell.mobile.widget.PublicApiClient
import com.junglebell.mobile.widget.RawLaundrySource
import com.junglebell.mobile.widget.WidgetSyncWorker
import kotlin.concurrent.thread

/**
 * One-time setup screen for the wash tower source: the user scans the QR
 * posted in the laundry room (or types the address) and the app validates it
 * by fetching the machine JSON once. The address stays on this device only —
 * widget updates then hit the source directly, without the server.
 */
class LaundrySourceActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var saveButton: Button
    private var validating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_laundry_source)

        input = findViewById(R.id.etLaundrySourceUrl)
        status = findViewById(R.id.tvLaundrySourceStatus)
        saveButton = findViewById(R.id.btnLaundrySourceSave)
        val clearButton = findViewById<Button>(R.id.btnLaundrySourceClear)

        val saved = LaundrySourceStore.url(this)
        val prefill = intent?.getStringExtra(EXTRA_PREFILL)
        if (!prefill.isNullOrBlank()) {
            input.setText(prefill)
            input.setSelection(0, prefill.length)
        } else if (saved != null) {
            input.setText(saved)
            status.text = "현재 연결됨: $saved"
        } else {
            status.text = "연결된 소스가 없습니다. 서버 공개 API로 세탁 정보를 가져오는 중입니다."
        }

        saveButton.setOnClickListener { validateAndSave() }
        clearButton.setOnClickListener {
            LaundrySourceStore.clear(this)
            status.text = "연결을 해제했습니다. 서버 공개 API로 되돌아갑니다."
            WidgetSyncWorker.enqueueOneTime(this)
            Toast.makeText(this, "연결 해제됨", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateAndSave() {
        if (validating) return
        val candidate = input.text.toString().trim()
        if (candidate.isEmpty()) {
            status.text = "주소를 입력하거나 QR을 스캔하세요."
            return
        }
        validating = true
        saveButton.isEnabled = false
        status.text = "검증 중… ($candidate)"
        thread(name = "laundry-source-validate") {
            val resolved = runCatching {
                RawLaundrySource.resolveAndFetch(candidate, PublicApiClient.httpClient)
            }.getOrNull()
            runOnUiThread {
                validating = false
                saveButton.isEnabled = true
                if (resolved == null) {
                    status.text = "검증 실패: 이 주소에서 워시타워 JSON을 찾지 못했습니다.\n사이트 주소 또는 …/api/status 주소를 확인하세요."
                    return@runOnUiThread
                }
                LaundrySourceStore.save(this, resolved.endpoint)
                val machineCount = resolved.snapshot.machines.size
                status.text = "저장 완료: ${resolved.endpoint}\n기기 ${machineCount}대 확인. 위젯을 즉시 갱신합니다."
                Toast.makeText(this, "워시타워 소스 연결됨", Toast.LENGTH_SHORT).show()
                WidgetSyncWorker.enqueueOneTime(this)
            }
        }
    }

    companion object {
        private const val EXTRA_PREFILL = "prefill_url"

        fun prefillIntent(context: Context, url: String): Intent =
            Intent(context, LaundrySourceActivity::class.java).apply {
                putExtra(EXTRA_PREFILL, url)
            }
    }
}
