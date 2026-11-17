package com.cabgon.blackhawk.data

import java.security.MessageDigest

object PackageManager {

    enum class Pkg { IADS, SIKORSKY }

    data class ManualMeta(
        val title: String,
        val remotePath: String,         // Firebase Storage path
        val id: String = stableId(remotePath),
        val localRelativePath: String = remotePath // por defecto igual (manual_cache/<remotePath>)
    )

    fun manuals(pkg: Pkg): List<ManualMeta> = when (pkg) {
        Pkg.IADS -> listOf(
            ManualMeta("Capítulo 1 General Information", "iads/manuals/Capitulo 1.pdf"),
            ManualMeta("Capítulo 2 Troubleshooting Procedures", "iads/manuals/Capitulo 2.pdf"),
            ManualMeta("Capítulo 3 Troubleshooting Procedures", "iads/manuals/Capitulo 3.pdf"),
            ManualMeta("Capítulo 4 Troubleshooting Procedures", "iads/manuals/Capitulo 4.pdf"),
            ManualMeta("Capítulo 5 Maintenance Instructions", "iads/manuals/Capitulo 5.pdf"),
            ManualMeta("Capítulo 6 Maintenance Instructions", "iads/manuals/Capitulo 6.pdf"),
            ManualMeta("Capítulo 7 Maintenance Instructions", "iads/manuals/Capitulo 7.pdf"),
            ManualMeta("Capítulo 8 Maintenance Instructions", "iads/manuals/Capitulo 8.pdf"),
            ManualMeta("Capítulo 9 Maintenance Instructions", "iads/manuals/Capitulo 9.pdf"),
            ManualMeta("Capítulo 10 Maintenance Instructions", "iads/manuals/Capitulo 10.pdf"),
            ManualMeta("Capítulo 11 Maintenance Instructions", "iads/manuals/Capitulo 11.pdf"),
            ManualMeta("Capítulo 12 Maintenance Instructions", "iads/manuals/Capitulo 12.pdf"),
            ManualMeta("Capítulo 13 Maintenance Instructions", "iads/manuals/Capitulo 13.pdf"),
            ManualMeta("Capítulo 14 Maintenance Instructions", "iads/manuals/Capitulo 14.pdf"),
            ManualMeta("Capítulo 15 Maintenance Instructions", "iads/manuals/Capitulo 15.pdf"),
            ManualMeta("Capítulo 16 Maintenance Instructions", "iads/manuals/Capitulo 16.pdf"),
            ManualMeta("Capítulo 17 Maintenance Instructions", "iads/manuals/Capitulo 17.pdf"),
            ManualMeta("Capítulo 18 Maintenance Instructions", "iads/manuals/Capitulo 18.pdf"),
            ManualMeta("Capítulo 19 Maintenance Instructions", "iads/manuals/Capitulo 19.pdf"),
            ManualMeta("Capítulo 20 Supporting Information", "iads/manuals/Capitulo 20.pdf"),
            ManualMeta("Maintenance Test Flight", "iads/manuals/MAINTENANCE TEST FLIGHT.pdf"),
            ManualMeta("Engine Aircraft", "iads/manuals/MANUAL DEL MOTOR.pdf"),
            ManualMeta("Manual del Operador", "iads/manuals/MANUAL DEL OPERADOR.pdf")
        )

        Pkg.SIKORSKY -> listOf(
            ManualMeta("Capítulo 1 Air Craft General", "sikorsky/manuals/Capitulo 1.pdf"),
            ManualMeta("Capítulo 2 Airframe", "sikorsky/manuals/Capitulo 2.pdf"),
            ManualMeta("Capítulo 3 Landing Gear", "sikorsky/manuals/Capitulo 3.pdf"),
            ManualMeta("Capítulo 4 Engine", "sikorsky/manuals/Capitulo 4.pdf"),
            ManualMeta("Capítulo 5 Rotor", "sikorsky/manuals/Capitulo 5.pdf"),
            ManualMeta("Capítulo 6 Drive", "sikorsky/manuals/Capitulo 6.pdf"),
            ManualMeta("Capítulo 7 Pneudraulic", "sikorsky/manuals/Capitulo 7.pdf"),
            ManualMeta("Capítulo 8 Instrument", "sikorsky/manuals/Capitulo 8.pdf"),
            ManualMeta("Capítulo 9 Electrical", "sikorsky/manuals/Capitulo 9.pdf"),
            ManualMeta("Capítulo 10 Fuel", "sikorsky/manuals/Capitulo 10.pdf"),
            ManualMeta("Capítulo 11 Flight", "sikorsky/manuals/Capitulo 11.pdf"),
            ManualMeta("Capítulo 12 Utility", "sikorsky/manuals/Capitulo 12.pdf"),
            ManualMeta("Capítulo 13 Environmental Control", "sikorsky/manuals/Capitulo 13.pdf"),
            ManualMeta("Capítulo 14 Hoist & Winches", "sikorsky/manuals/Capitulo 14.pdf"),
            ManualMeta("Capítulo 15 Auxiliary Power Plant", "sikorsky/manuals/Capitulo 15.pdf"),
            ManualMeta("Capítulo 16 Mission Equipment", "sikorsky/manuals/Capitulo 16.pdf"),
            ManualMeta("Capítulo 17 Emergency Equipment", "sikorsky/manuals/Capitulo 17.pdf"),
            ManualMeta("Capítulo 18 Ground Support Equipment", "sikorsky/manuals/Capitulo 18.pdf"),
            ManualMeta("Maintenance Repair, Parts & Special Tools", "sikorsky/manuals/MAINTENANCE REPAIR PARTS AND SPECIAL.pdf"),
            ManualMeta("Manual del Operador", "sikorsky/manuals/MANUAL DEL OPERADOR.pdf"),
            ManualMeta("Avionics Repair, Parts & Special Tools", "sikorsky/manuals/AVIONICS REPAIR PARTS AND SPECIAL.pdf"),
            ManualMeta("Apendix A References", "sikorsky/manuals/Appendix A.pdf"),
            ManualMeta("Apendix B Metric Conversion Charts", "sikorsky/manuals/Appendix B.pdf"),
            ManualMeta("Apendix C Special Tool", "sikorsky/manuals/Appendix C.pdf"),
            ManualMeta("Apendix D Expandable & Durable Item", "sikorsky/manuals/Appendix D.pdf"),
            ManualMeta("Apendix E Storage", "sikorsky/manuals/Appendix E.pdf"),
            ManualMeta("Apendix F Wiring", "sikorsky/manuals/Appendix F.pdf"),
            ManualMeta("Apendix G Weight & Balance", "sikorsky/manuals/Appendix G.pdf"),
            ManualMeta("Apendix H Illustrated List", "sikorsky/manuals/Appendix H.pdf")
        )
    }

    fun indexAssetPath(pkg: Pkg): String = when (pkg) {
        Pkg.IADS -> "iads/index/blackhawk_iads_fts.db"
        Pkg.SIKORSKY -> "sikorsky/index/blackhawk_sikorsky_fts.db"
    }

    private fun stableId(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
