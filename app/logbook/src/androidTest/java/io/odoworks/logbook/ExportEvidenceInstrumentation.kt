package io.odoworks.logbook

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** 차량의 보조 사용자 저장 공간도 앱 권한으로 읽어 CSV 증거 사본을 만든다. */
class ExportEvidenceInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        try {
            val directory = requireNotNull(targetContext.getExternalFilesDir("exports"))
            val source = requireNotNull(directory.listFiles()?.singleOrNull { it.extension == "csv" })
            val bytes = source.readBytes()
            check(bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) { "UTF-8 BOM 누락" }
            val text = source.readText(Charsets.UTF_8)
            check(text.contains("\"사용일자(요일)\",\"사용자(부서)\",\"사용자(성명)\"")) { "서식 열 누락" }
            val copy = File(targetContext.cacheDir, "export-evidence.csv")
            copy.writeBytes(bytes)
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "CSV BOM·서식 열 검증 통과; 내부 캐시 증거 사본: ${copy.name}\n")
            })
        } catch (e: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "CSV 검증 실패: ${e.message}\n") })
        }
    }
}
