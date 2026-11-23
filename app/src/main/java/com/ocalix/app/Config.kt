package com.ocalix.app

/** Padanan config.js di versi Node. */
object Config {
    const val HOST = "127.0.0.1"   // server hanya bisa diakses dari dalam HP
    const val PORT = 7777          // kalau terpakai, otomatis coba 7778..7786
    const val DOMAIN = "localhost" // hanya untuk /api/config
    val AUDIO = true               // false = matikan mode audio latar belakang
    val PIPED: List<String> = emptyList() // opsional: instance Piped sendiri
    val PIPED_ENABLED = true       // false = matikan jalur cadangan Piped
}
