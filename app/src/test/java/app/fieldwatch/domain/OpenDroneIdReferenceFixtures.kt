package app.fieldwatch.domain

/**
 * Encoded by the independent opendroneid-core-c reference encoder, rather than
 * building bytes with Fieldwatch's decoder offsets.
 * Generator and regeneration instructions: src/test/reference/opendroneid/.
 * https://github.com/opendroneid/opendroneid-core-c/tree/849c03adbe0cdd68997a462fafab397edcfe53f3
 * opendroneid.c SHA-256: 60b0964f5f2a0dc13833eb6a304f7bd3f64bb9c227fae168c7caba53c82927c2
 * Protocol 2, airborne multirotor, CTA serial 1581F3YTDJ1D0031Z530.
 * Location A: 40, -74, heading 90, speed 10 m/s, vertical 2 m/s, altitude 100 m.
 * Location B: 40.001, -74.002, heading 135, speed 12 m/s, altitude 101 m.
 * System A: takeoff 39.9, -73.9, altitude 50 m.
 * System B: live GNSS 39.901, -73.902, altitude 55 m.
 */
internal object OpenDroneIdReferenceFixtures {
    const val BASIC = "02123135383146335954444A3144303033315A353330000000"
    const val LOCATION_A = "12205A28040084D717007FE4D3000098080000000000000000"
    const val LOCATION_B = "122087300410ABD717E030E4D300009A080000000000000000"
    const val SYSTEM_A = "4200C041C81740C1F3D3010000000000000034080000000000"
    const val SYSTEM_B = "4201D068C8172073F3D301000000000000003E080000000000"
    const val PACK_A = "F21903" + BASIC + LOCATION_A + SYSTEM_A
    // Maximum nine-message pack: the same three records plus Auth pages 0..5.
    const val PACK_MAX = "F21909" + BASIC + LOCATION_A + SYSTEM_A +
        "22000584000000000000000000000000000000000000000000" +
        "22010000000000000000000000000000000000000000000000" +
        "22020000000000000000000000000000000000000000000000" +
        "22030000000000000000000000000000000000000000000000" +
        "22040000000000000000000000000000000000000000000000" +
        "22050000000000000000000000000000000000000000000000"
}
