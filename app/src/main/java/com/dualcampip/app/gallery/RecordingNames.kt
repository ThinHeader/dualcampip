package com.dualcampip.app.gallery

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val fileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

fun recordingDisplayName(time: ZonedDateTime = ZonedDateTime.now()): String {
    return "DualCam_${time.format(fileStamp)}.mp4"
}
