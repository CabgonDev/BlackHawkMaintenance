package com.cabgon.blackhawk.data
object PackageManager {
    enum class Pkg { IADS, SIKORSKY }
    fun manuals(pkg: Pkg): List<ManualMeta> = when (pkg) {
        Pkg.IADS -> listOf(
            ManualMeta("TM 1-1520-L-23-1", "iads/manuals/TM_1-1520-L-23-1.pdf"),
            ManualMeta("TM 1-1520-L-23-2", "iads/manuals/TM_1-1520-L-23-2.pdf")
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
            ManualMeta("Apendix A References", "sikorsky/manuals/Apendix A.pdf"),
            ManualMeta("Apendix B Metric Conversion Charts", "sikorsky/manuals/Apendix B.pdf"),
            ManualMeta("Apendix C Special Tool", "sikorsky/manuals/Apendix C.pdf"),
            ManualMeta("Apendix D Expandable & Durable Item", "sikorsky/manuals/Apendix D.pdf"),
            ManualMeta("Apendix E Storage", "sikorsky/manuals/Apendix E.pdf"),
            ManualMeta("Apendix F Wiring", "sikorsky/manuals/Apendix F.pdf"),
            ManualMeta("Apendix G Weight & Balance", "sikorsky/manuals/Apendix G.pdf"),
            ManualMeta("Apendix H Illustrated List", "sikorsky/manuals/Apendix H.pdf")


        )
    }
    fun indexAssetPath(pkg: Pkg): String = when (pkg) {
        Pkg.IADS -> "iads/index/blackhawk_iads_fts.db"
        Pkg.SIKORSKY -> "sikorsky/index/blackhawk_sikorsky_fts.db"
    }
}