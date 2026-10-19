package com.cast16x9.app.dlna

data class DlnaDevice(
    val friendlyName: String,
    val location: String,
    val controlUrl: String,
    val udn: String
)
