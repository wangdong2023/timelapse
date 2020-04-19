package com.shallow_mind.timelapse

import android.annotation.SuppressLint
import java.text.SimpleDateFormat
import java.util.*

class TimeService {
    companion object {
        @SuppressLint("SimpleDateFormat")
        public fun getForDirectory(): String {
            return SimpleDateFormat("yyMMddHH").format(Date())
        }

        @SuppressLint("SimpleDateFormat")
        public fun getForFile(): String {
            return SimpleDateFormat("yyMMdd_HHmmss").format(Date())
        }

        @SuppressLint("SimpleDateFormat")
        public fun getForLog(): String {
            return SimpleDateFormat("mm:ss.SSS").format(Date())
        }
    }

}